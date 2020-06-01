package main;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import dataBase.DataBase;
import main.Message;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

// 一个LinkThread对应一个用户的连接
public class LinkThread extends Thread {
	public static ServerSocket server;
	public static DataBase dataBase;
	private Socket socket;
	private String username;

	public LinkThread() {
	}

	public void run() {
		while (true) {
			try {
				// 没有用户连接之前，线程会一直阻塞在这一步
				socket = server.accept();

				// 各个流初始化
				InputStream inputStream = socket.getInputStream();
				DataInputStream dataInputStream = new DataInputStream(inputStream);
				OutputStream outputStream = socket.getOutputStream();
				DataOutputStream dataOutputStream = new DataOutputStream(outputStream);

				Message message;

				// 连接初始化
				message = new Message();
				message.message1(dataBase, dataOutputStream);

				// 循环接收登录请求或者注册请求
				while (true) {
					message = Message.receiveMessage(dataBase, dataInputStream);
					int messageNumber = Integer.parseInt(message.getMessageNumber());
					// 2号请求
					if (messageNumber == 2) {
						// 需要记录一下用户名
						username = message.getMessageField1();
						if (message.message2(dataBase, dataOutputStream)) {
							System.out.println("用户" + "[" + username + "]" + "已登录");
							// 添加socket关联
							dataBase.addSocket(username, socket);
							// 跳出循环
							break;
						} else {
							System.out.println("用户" + "[" + username + "]" + "登录出错");
						}
					}
					// 3号请求
					else if (messageNumber == 3) {
						username = message.getMessageField1();
						if (message.message3(dataBase, dataOutputStream)) {
							System.out.println("用户" + "[" + username + "]" + "已注册");
						} else {
							System.out.println("用户" + "[" + username + "]" + "注册出错");
						}
					}
					// 其余请求直接跳过
				}

				// 接下来就是循环读取请求了
				while (true) {
					message = Message.receiveMessage(dataBase, dataInputStream);
					int messageNumber = Integer.parseInt(message.getMessageNumber());
					switch (messageNumber) {
					// 获取好友列表
					case 4:
						message.message4(dataBase, username, dataOutputStream);
						break;
					// 获取历史消息列表
					case 5:
						message.message5();
						break;
					// 创建会话
					case 6:
						message.message6(dataBase, username, dataOutputStream);
						break;
					// 加入会话
					case 7:
						message.message7(dataBase, dataOutputStream);
						break;
					// 发送消息
					case 9:
						message.message9(dataBase, username);
						break;
					// 添加好友
					case 10:
						message.message10(dataBase, dataOutputStream, username);
						break;
					}
				}
			}
			// 连接异常断开时，维护socketTable
			catch (SocketException socketException) {
				System.out.println("用户" + "[" + username + "]" + "已登出");
				dataBase.delSocket(username);
			}
			// 其他异常的情况回头仔细考虑
			catch (Exception e) {
				System.err.println("异常：" + e);
			}
		}

	}
}
