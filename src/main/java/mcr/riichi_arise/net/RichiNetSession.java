package mcr.riichi_arise.net;

import com.google.gson.JsonObject;
import io.netty.channel.Channel;

import java.util.HashSet;
import java.util.Set;

/**
 * 一条内网 TCP 接入连接（对应一个外部程序）。
 * <p>线程模型：IO 在 Netty 事件循环线程，业务（协议分发、读/写牌局状态）一律投递到
 * MC 服务端主线程执行；{@link #send} 从主线程调用（Netty writeAndFlush 线程安全）。
 */
public final class RichiNetSession {
	/** 底层 channel（作为会话唯一 key） */
	public final Channel channel;
	/** 是否已完成 hello（生成身份后才能 join/action） */
	public volatile boolean greeted;
	/** 外部玩家身份 UUID（hello 时生成；join 时作为牌局座位 UUID） */
	public volatile String uuid;
	/** 外部玩家显示名 */
	public volatile String name;
	/** 已订阅的牌桌 key（"x,y,z"）：这些桌快照变化时推送 */
	public final Set<String> subscribed = new HashSet<>();
	/** 订阅快照是否隐藏他家手牌（默认 false = 给完整牌面，内部工具常用） */
	public volatile boolean hideOthers;
	/** 当前已加入的牌桌 key（null = 未加入） */
	public volatile String joinedTable;
	/** 当前座位（-1 = 未入座；join 后由开局分配，对局中有效） */
	public volatile int seat = -1;

	public RichiNetSession(Channel channel) {
		this.channel = channel;
	}

	/** 从主线程发送一条 JSON 消息（换行分隔，NDJSON） */
	public void send(JsonObject obj) {
		if (channel != null && channel.isActive()) {
			channel.writeAndFlush(obj.toString() + "\n");
		}
	}

	/** 会话是否已连接（供推送时过滤断线会话） */
	public boolean isActive() {
		return channel != null && channel.isActive();
	}

	/** 关闭连接（主线程调用；异步写后关闭） */
	public void close() {
		if (channel != null && channel.isActive())
			channel.close();
	}
}
