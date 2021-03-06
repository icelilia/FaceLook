package dataBase;

import java.net.Socket;
import java.util.Date;
import java.util.Hashtable;
import java.util.Set;
import java.util.Vector;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.bson.Document;
import com.alibaba.fastjson.JSON;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.FindIterable;

import dataBase.MongoDBAPI;
import dataBase.entity.*;

public class DataBase {
	/**
	 * mongoDB地址
	 */
	private static final String MONGODB_SERVER_HOST = "mongodb://Andersen:213533@175.24.10.214:27017/?authSource=admin&readPreference=primary&appname=MongoDB%20Compass&ssl=false";

	/**
	 * mongodb对象的引用
	 */
	private final static MongoClient mongoClient = new MongoClient(new MongoClientURI(MONGODB_SERVER_HOST));

	/**
	 * FaceLook数据库的引用
	 */
	private final static MongoDatabase faceLook = MongoDBAPI.getOrCreateDatabase(mongoClient, "FaceLook");

	private static final String USER_COLLECTION_NAME = "userCollection";
	/**
	 * 用户collection的引用
	 */
	@SuppressWarnings("unused")
	private final static MongoCollection<Document> userCollection = MongoDBAPI.getOrCreateCollection(faceLook,
			USER_COLLECTION_NAME);

	private static final String SESSION_COLLECTION_NAME = "sessionCollection";
	/**
	 * 对话collection的引用
	 */
	@SuppressWarnings("unused")
	private final static MongoCollection<Document> sessionCollection = MongoDBAPI.getOrCreateCollection(faceLook,
			SESSION_COLLECTION_NAME);

	/**
	 * <用户名, socket>关联
	 */
	private Hashtable<String, Socket> socketTable = new Hashtable<String, Socket>();

	/**
	 * 构造函数，同时包含了一些初始化工作
	 */
	private DataBase() {
		// 数据库初始化工作
		// 获取当前最大的sessionId，以防重启服务端后生成的sessionId和以前的重复
		Document document = MongoDBAPI.getMax(faceLook, SESSION_COLLECTION_NAME, "sessionId");
		try {
			int maxSessionId = JSON.parseObject(document.toJson(), Session.class).getSessionId();
			Session.sessionNum = maxSessionId;
		} catch (Exception e) {
			Session.sessionNum = 0;
		}

	}

	/**
	 * 单例模式
	 */
	private final static DataBase dataBaseInstance = new DataBase();

	/**
	 * 获得数据库单例对象的引用
	 * 
	 * @return 数据库单例对象的引用
	 */
	public static DataBase getDataBaseInstance() {
		return dataBaseInstance;
	}

	/* 数据库相关 */

	/**
	 * 检验用户名唯一性。
	 * 
	 * @param username 待检验的用户名
	 * @return true：该用户名唯一；false：该用户名已存在
	 */
	synchronized public boolean checkUsernameUniqueness(final String username) {
		Document document = new Document("username", username);
		Document searchResult = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, document);
		return !isValidDocument(searchResult);
	}

	/**
	 * 注册用户，将用户注册至数据库中。注意，该方法并不对待注册的用户对象做任何格式检查。
	 * 
	 * @param user 待注册的用户对象
	 */
	synchronized public void registerUser(final User user) {
		Document userDocument = Document.parse(JSON.toJSONString(user));
		MongoDBAPI.insertOneDocument(faceLook, USER_COLLECTION_NAME, userDocument);
	}

	/**
	 * 检验登录。登录时检验用户名和密码是否匹配。
	 * 
	 * @param username 登录的用户名
	 * @param password 登录的密码
	 * @return true：匹配，登录成功；false：不匹配，登录失败
	 */
	synchronized public boolean checkLogin(final String username, final String password) {
		Document searchDocument = new Document("username", username).append("password", password);
		Document searchResult = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, searchDocument);
		return isValidDocument(searchResult);
	}

	synchronized public void updateUserInfo(User user) {
		Document searchUserDocument = new Document("username", user.getUsername());
		Document userDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, searchUserDocument);
		User originUser = JSON.parseObject(userDocument.toJson(), User.class);

		originUser.setNickname(user.getNickname());
		originUser.setAvatarAddress(user.getAvatarAddress());
		originUser.setEmail(user.getEmail());
		originUser.setPhoneNumber(user.getPhoneNumber());
		originUser.setOccupation(user.getOccupation());
		originUser.setLocation(user.getLocation());

		Document newUserDocument = Document.parse(JSON.toJSONString(originUser));
		MongoDBAPI.updateOneDocument(faceLook, USER_COLLECTION_NAME, searchUserDocument, newUserDocument);
	}

	synchronized public User getUserByUsername(String username) {
		Document searchUserDocument = new Document("username", username);
		Document userDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, searchUserDocument);
		return JSON.parseObject(userDocument.toJson(), User.class);
	}

	/**
	 * 获得指定用户的好友列表。
	 * 
	 * @param username 指定用户的用户名
	 * @return 指定用户的好友列表，一个元素类型为Friend的Vector
	 */
	synchronized public Vector<User> getFriends(final String username) {
		Document document = new Document("username", username);
		Document searchResult = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, document);
		User user = JSON.parseObject(searchResult.toJson(), User.class);

		Vector<String> friendUsernames = user.getFriendUsernames();
		Vector<User> friends = new Vector<User>(friendUsernames.size());

		User friend;
		for (String friendUsername : friendUsernames) {
			document = new Document("username", friendUsername);
			searchResult = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, document);
			friend = JSON.parseObject(searchResult.toJson(), User.class);

			friend.justInformation();
			friends.add(friend);
		}
		return friends;
	}

	/**
	 * 创建会话。创建者将自动加入新会话，同时默认创建者为新会话的manager。
	 * 
	 * @param username 创建者的用户名
	 * @return 新创建的会话的sessionId
	 */
	synchronized public int createSession(String username) {
		// 获取用户
		Document searchUserDocument = new Document("username", username);
		Document userDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, searchUserDocument);
		User user = JSON.parseObject(userDocument.toJson(), User.class);

		// 新建session
		Session.sessionNum++;
		Session session = new Session();

		int sessionId = session.getSessionId();

		// session中添加用户
		session.addSessionMember(user.getUsername());

		// 用户中添加session
		user.addSession(sessionId);

		// 记录入库
		Document sessionDocument = Document.parse(JSON.toJSONString(session));
		MongoDBAPI.insertOneDocument(faceLook, SESSION_COLLECTION_NAME, sessionDocument);

		Document newUserDocument = Document.parse(JSON.toJSONString(user));
		MongoDBAPI.updateOneDocument(faceLook, USER_COLLECTION_NAME, userDocument, newUserDocument);

		return sessionId;
	}

	/**
	 * 创建群聊。创建者将自动加入新会话，同时默认创建者为新会话的manager。
	 * 
	 * @param username    创建者的用户名
	 * @param sessionName 创建的群聊的名称
	 * @return 新创建的会话的sessionId
	 */
	synchronized public int createSession(String username, String sessionName) {
		// 获取用户
		Document searchUserDocument = new Document("username", username);
		Document userDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, searchUserDocument);
		User user = JSON.parseObject(userDocument.toJson(), User.class);

		// 新建session
		Session.sessionNum++;
		Session session = new Session();

		// 设置群聊名称
		session.setSessionName(sessionName);

		// 默认创建者为群主
		session.setManagerUsername(username);

		int sessionId = session.getSessionId();

		// session中添加用户
		session.addSessionMember(user.getUsername());

		// 用户中添加session
		user.addSession(sessionId);

		// 写回
		Document sessionDocument = Document.parse(JSON.toJSONString(session));
		MongoDBAPI.insertOneDocument(faceLook, SESSION_COLLECTION_NAME, sessionDocument);

		Document newUserDocument = Document.parse(JSON.toJSONString(user));
		MongoDBAPI.updateOneDocument(faceLook, USER_COLLECTION_NAME, userDocument, newUserDocument);

		return sessionId;
	}

	synchronized public String getSessionName(int sessionId) {
		Document searchSessionDocument = new Document("sessionId", sessionId);
		Document sessionDocument = MongoDBAPI.findOneDocument(faceLook, SESSION_COLLECTION_NAME, searchSessionDocument);
		Session session = JSON.parseObject(sessionDocument.toJson(), Session.class);
		return session.getSessionName();
	}

	synchronized public String getSessionManager(int sessionId) {
		Document searchSessionDocument = new Document("sessionId", sessionId);
		Document sessionDocument = MongoDBAPI.findOneDocument(faceLook, SESSION_COLLECTION_NAME, searchSessionDocument);
		Session session = JSON.parseObject(sessionDocument.toJson(), Session.class);
		return session.getManagerUsername();
	}

	/**
	 * 将指定用户加入指定对话。 会判断指定用户是否已经在指定会话中，如果是，则什么也不做；
	 * 如果不是，那么将在指定用户的会话列表中添加指定会话，并在指定会话的成员列表中添加指定用户。
	 * 
	 * @param inviteeUsername 指定用户的用户名
	 * @param sessionId       指定会话的sessionId
	 */
	synchronized public boolean joinSession(String inviteeUsername, int sessionId) {
		Document searchSessionDocument = new Document("sessionId", sessionId);
		Document sessionDocument = MongoDBAPI.findOneDocument(faceLook, SESSION_COLLECTION_NAME, searchSessionDocument);
		Session session = JSON.parseObject(sessionDocument.toJson(), Session.class);

		// 先判断用户是否已经在会话中
		Vector<String> members = session.getSessionMembers();
		for (String member : members) {
			// 用户已经在会话中
			if (member.contentEquals(inviteeUsername)) {
				return false;
			}
		}

		// 用户不在会话中
		Document searchInviteeUserDocument = new Document("username", inviteeUsername);
		Document inviteeUserDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME,
				searchInviteeUserDocument);
		User inviteeUser = JSON.parseObject(inviteeUserDocument.toJson(), User.class);

		// 更新会话中的成员列表
		session.addSessionMember(inviteeUsername);
		Document newSessionDocument = Document.parse(JSON.toJSONString(session));
		MongoDBAPI.updateOneDocument(faceLook, SESSION_COLLECTION_NAME, sessionDocument, newSessionDocument);

		// 更新用户的会话列表
		inviteeUser.addSession(sessionId);
		Document newInviteeUserDocument = Document.parse(JSON.toJSONString(inviteeUser));
		MongoDBAPI.updateOneDocument(faceLook, USER_COLLECTION_NAME, inviteeUserDocument, newInviteeUserDocument);
		return true;

	}

	/**
	 * 退出对话
	 * 
	 * @param username
	 * @param sessionId
	 * @return
	 */
	synchronized public void quitSession(String username, int sessionId) {
		Document searchSessionDocument = new Document("sessionId", sessionId);
		Document sessionDocument = MongoDBAPI.findOneDocument(faceLook, SESSION_COLLECTION_NAME, searchSessionDocument);
		Session session = JSON.parseObject(sessionDocument.toJson(), Session.class);

		Document searchUserDocument = new Document("username", username);
		Document userDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, searchUserDocument);
		User user = JSON.parseObject(userDocument.toJson(), User.class);

		// 更新会话集合中用户信息
		Vector<String> sessionMembers = session.getSessionMembers();
		for (String str : sessionMembers) {
			if (str.contentEquals(username)) {
				sessionMembers.remove(str);
				break;
			}
		}
		// 更新用户下面的会话列表
		Vector<Integer> sessionIds = user.getSessionIds();
		for (Integer i : sessionIds) {
			if (i == sessionId) {
				sessionIds.remove(i);
				break;
			}
		}
		Document newSessionDocument = Document.parse(JSON.toJSONString(session));
		MongoDBAPI.updateOneDocument(faceLook, SESSION_COLLECTION_NAME, searchSessionDocument, newSessionDocument);

		Document newUserDocument = Document.parse(JSON.toJSONString(user));
		MongoDBAPI.updateOneDocument(faceLook, USER_COLLECTION_NAME, searchUserDocument, newUserDocument);
	}

	/**
	 * 获得指定用户的会话列表。
	 * 
	 * @param username 指定用户的用户名
	 * @return 一个包含该用户所在的所有会话的sessionId的Vector
	 */
	synchronized public Vector<Integer> getSessions(String username) {
		Document searchUserDocument = new Document("username", username);
		Document userDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, searchUserDocument);
		User user = JSON.parseObject(userDocument.toJson(), User.class);
		return user.getSessionIds();

	}

	/**
	 * 获得指定会话的成员列表。
	 * 
	 * @param sessionId 指定会话的sessinId
	 * @return 一个包含该会话中所有成员的username的Vector
	 */
	synchronized public Vector<String> getMembers(int sessionId) {
		Document searchSessionDocument = new Document("sessionId", sessionId);
		Document sessionDocument = MongoDBAPI.findOneDocument(faceLook, SESSION_COLLECTION_NAME, searchSessionDocument);
		Session session = JSON.parseObject(sessionDocument.toJson(), Session.class);
		return session.getSessionMembers();
	}

	/**
	 * 发送方发出好友或群聊申请。无论接收方是否在线，该申请均会被添加至接收方的申请列表里。
	 * 
	 * @param requestorUsername 发送方的用户名
	 * @param receiverUsername  好友请求：接收方的用户名；群聊请求：null
	 * @param checkMessage      验证信息
	 * @param sessionId         好友请求：“0”；群聊请求：sessionId
	 * @return 好友请求：接收方的用户名；群聊请求：群主用户名
	 */
	synchronized public String addRequest(String requestorUsername, String receiverUsername, String checkMessage,
			int sessionId, Date date) {
		// 判断
		if (receiverUsername == null) {
			receiverUsername = getSessionManager(sessionId);
		}

		// 获取接收方用户对象
		Document searchReceiverDocument = new Document("username", receiverUsername);
		Document receiverDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, searchReceiverDocument);
		User receiver = JSON.parseObject(receiverDocument.toJson(), User.class);

		Request request = new Request(sessionId, requestorUsername, checkMessage, date);

		// 添加请求
		receiver.addRequest(request);

		// 写回
		Document newReceiverDocument = Document.parse(JSON.toJSONString(receiver));
		MongoDBAPI.updateOneDocument(faceLook, USER_COLLECTION_NAME, receiverDocument, newReceiverDocument);

		return receiverUsername;
	}

	/**
	 * 接收方处理好友申请。无论发送方是否在线，该申请均会被从接收方的申请列表里清除。 并且会将结果添加至发送方的结果列表里。
	 * 
	 * @param requestorUsername 发送方的用户名
	 * @param receiverUsername  接收方的用户名
	 * @param result            构造的结果对象
	 */
	synchronized public void checkRequest(final String requestorUsername, final String receiverUsername,
			final int sessionId, final String result) {

		// 获取接收方用户对象
		Document searchReceiverDocument = new Document("username", receiverUsername);
		Document receiverDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, searchReceiverDocument);
		User receiver = JSON.parseObject(receiverDocument.toJson(), User.class);

		// 获取申请方用户对象
		Document searchRequestorDocument = new Document("username", requestorUsername);
		Document requestorDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME,
				searchRequestorDocument);
		User requestor = JSON.parseObject(requestorDocument.toJson(), User.class);

		// 将申请方从接收方的请求列表里删除
		boolean hadRequest = false;
		Vector<Request> requests = receiver.getRequests();
		for (Request tempRequest : requests) {
			if (tempRequest.getRequestorUsername().contentEquals(requestorUsername)
					&& tempRequest.getSessionId().contentEquals(String.valueOf(sessionId))) {
				requests.remove(tempRequest);
				hadRequest = true;
				break;
			}
		}

		// 并未在申请列表里则直接结束，什么也不做
		if (!hadRequest) {
			return;
		}

		// 好友请求
		if (sessionId == 0) {
			// 若同意，则双方互相成为好友
			if (result.contentEquals("1")) {
				receiver.getFriendUsernames().add(requestorUsername);
				requestor.getFriendUsernames().add(receiverUsername);
			}
		}
		// 群聊请求
		else {
			if (result.contentEquals("1")) {
				joinSession(requestorUsername, sessionId);
			}
		}
		// 结果

		Document newReceiverDocument = Document.parse(JSON.toJSONString(receiver));
		MongoDBAPI.updateOneDocument(faceLook, USER_COLLECTION_NAME, receiverDocument, newReceiverDocument);

		requestorDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, searchRequestorDocument);
		requestor.getResults().add(new Result(sessionId, receiverUsername, result, new Date()));
		Document newRequestorDocument = Document.parse(JSON.toJSONString(requestor));
		MongoDBAPI.updateOneDocument(faceLook, USER_COLLECTION_NAME, requestorDocument, newRequestorDocument);
	}

	synchronized public void delFriend(String activeUsername, String passiveUsername) {
		// 获取两个用户对象
		Document searchActiveUserDocument = new Document("username", activeUsername);
		Document activeUserDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME,
				searchActiveUserDocument);
		User activeUser = JSON.parseObject(activeUserDocument.toJson(), User.class);

		Document searchPassiveUserDocument = new Document("username", passiveUsername);
		Document passiveUserDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME,
				searchPassiveUserDocument);
		User passiveUser = JSON.parseObject(passiveUserDocument.toJson(), User.class);

		// 删除好友关系后写回
		activeUser.getFriendUsernames().remove(passiveUsername);
		Document newActiveUserDocument = Document.parse(JSON.toJSONString(activeUser));
		MongoDBAPI.updateOneDocument(faceLook, USER_COLLECTION_NAME, activeUserDocument, newActiveUserDocument);

		passiveUser.getFriendUsernames().remove(activeUsername);
		Document newPassiveUserDocument = Document.parse(JSON.toJSONString(passiveUser));
		MongoDBAPI.updateOneDocument(faceLook, USER_COLLECTION_NAME, passiveUserDocument, newPassiveUserDocument);
	}

	/**
	 * 获得指定用户的申请列表。
	 * 
	 * @param username 指定用户的用户名
	 * @return 指定用户的申请列表，一个元素类型为Request的Vector
	 */
	synchronized public Vector<Request> getRequests(String username) {
		Document searchUserDocument = new Document("username", username);
		Document userDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, searchUserDocument);
		User user = JSON.parseObject(userDocument.toJson(), User.class);
		return user.getRequests();
	}

	/**
	 * 获得指定用户的结果列表
	 * 
	 * @param username 指定用户的用户名
	 * @return 指定用户的结果列表，一个元素类型为Result的Vector
	 */
	synchronized public Vector<Result> getResults(String username) {
		Document searchUserDocument = new Document("username", username);
		Document userDocument = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, searchUserDocument);
		User user = JSON.parseObject(userDocument.toJson(), User.class);
		return user.getResults();
	}

	// 模糊查询，根据nickname查询
	public Vector<User> fuzzySearchByNickname(String nickname) {
		Pattern pattern = Pattern.compile(nickname);
		Document result = new Document("nickname", pattern);
		FindIterable<Document> documents = MongoDBAPI.findDocument(faceLook, USER_COLLECTION_NAME, result);
		Vector<User> users = new Vector<User>();
		documents.forEach((Consumer<Document>) document -> users.add(JSON.parseObject(document.toJson(), User.class)));
		return users;
	}

	// 准确查询，根据username查询
	public User searchByUsername(String username) {
		Document result = new Document("username", username);
		Document document = MongoDBAPI.findOneDocument(faceLook, USER_COLLECTION_NAME, result);
		if (document != null) {
			return JSON.parseObject(document.toJson(), User.class);
		}
		return null;
	}

	/**
	 * 检验Document的合法性
	 * 
	 * @param document 待检验Document的引用
	 * @return true：合法；false：不合法
	 */
	private boolean isValidDocument(Document document) {
		return (document != null) && (!document.isEmpty());
	}

	/* socket表相关 */

	/**
	 * 在socketTable中添加<username, socket>关联。
	 * 
	 * @param username 指定用户的用户名
	 * @param socket   指定连接的socket引用
	 */
	synchronized public void addSocket(String username, Socket socket) {
		if (username != null) {
			socketTable.put(username, socket);
		}
	}

	/**
	 * 根据用户名查找socket，可用来检验用户是否在线。
	 * 
	 * @param username 待查找用户的用户名
	 * @return 对应连接的socket引用
	 */
	synchronized public Socket searchSocketByUsername(String username) {
		if (username != null) {
			return socketTable.get(username);
		}
		return null;
	}

	/**
	 * 在socketTable中删除<username, socket>关联。
	 * 
	 * @param username 待删除用户的用户名
	 */
	synchronized public void delSocket(String username) {
		if (username != null && searchSocketByUsername(username) != null) {
			socketTable.remove(username);
		}
	}

	public Set<String> showSocket() {
		return socketTable.keySet();
	}
}
