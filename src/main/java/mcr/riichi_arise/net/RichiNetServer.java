package mcr.riichi_arise.net;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内网接入 TCP 服务器（Netty）。
 * <p>监听一个端口，接收外部程序的 NDJSON 行消息。收到消息/连接断开时把事件投递到
 * MC 服务端主线程，由 {@link RichiNetManager} 处理业务；网络 IO 线程只做编解码。
 * 默认关闭，经 /richi net start 显式开启（绑定 127.0.0.1）。
 */
public final class RichiNetServer {
	/** 会话集合（channel → session；主线程读写，并发读安全用 CHM） */
	final Map<Channel, RichiNetSession> sessions = new ConcurrentHashMap<>();

	private final MinecraftServer server;
	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private Channel serverChannel;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private volatile int port;
	private volatile String bind;

	public RichiNetServer(MinecraftServer server, int port, String bind) {
		this.server = server;
		this.port = port;
		this.bind = bind;
	}

	/** 在服务端主线程调用：启动监听（同步等待绑定结果，管理员低频操作可接受短暂阻塞） */
	public boolean start() {
		if (running.get())
			return false;
		bossGroup = new NioEventLoopGroup(1, new NamedThreadFactory("RichiNet-Boss"));
		workerGroup = new NioEventLoopGroup(2, new NamedThreadFactory("RichiNet-Worker"));
		try {
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup)
					.channel(NioServerSocketChannel.class)
					.childOption(ChannelOption.TCP_NODELAY, true)
					.childOption(ChannelOption.SO_KEEPALIVE, true)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							RichiNetSession session = new RichiNetSession(ch);
							sessions.put(ch, session);
							ch.pipeline()
									.addLast(new LineBasedFrameDecoder(RichiNetConfig.MAX_LINE_BYTES))
									.addLast(new StringDecoder(StandardCharsets.UTF_8))
									.addLast(new StringEncoder(StandardCharsets.UTF_8))
									.addLast(new SimpleChannelInboundHandler<String>() {
										@Override
										protected void channelRead0(ChannelHandlerContext ctx, String line) {
											// 投递到 MC 主线程执行业务，避免在 Netty IO 线程触碰牌局状态
											server.execute(() -> RichiNetManager.handle(session, line));
										}

										@Override
										public void channelInactive(ChannelHandlerContext ctx) {
											server.execute(() -> RichiNetManager.onDisconnect(session));
										}

										@Override
										public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
											// 单条异常：断线清理由 channelInactive 处理，避免中断其它连接
											ctx.close();
										}
									});
						}
					});
			ChannelFuture f = b.bind(bind, port).syncUninterruptibly();
			if (!f.isSuccess()) {
				shutdownGroups();
				return false;
			}
			serverChannel = f.channel();
			running.set(true);
			return true;
		} catch (Exception e) {
			shutdownGroups();
			return false;
		}
	}

	public void stop() {
		running.set(false);
		if (serverChannel != null) {
			serverChannel.close().awaitUninterruptibly(2000);
			serverChannel = null;
		}
		shutdownGroups();
	}

	private void shutdownGroups() {
		if (bossGroup != null) {
			bossGroup.shutdownGracefully();
			bossGroup = null;
		}
		if (workerGroup != null) {
			workerGroup.shutdownGracefully();
			workerGroup = null;
		}
	}

	public boolean isRunning() {
		return running.get();
	}

	public int port() {
		return port;
	}

	public String bind() {
		return bind;
	}

	public int connectionCount() {
		return sessions.size();
	}

	/** Netty 线程命名工厂（便于日志/诊断） */
	private static final class NamedThreadFactory implements java.util.concurrent.ThreadFactory {
		private final String prefix;
		private int n;

		NamedThreadFactory(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, prefix + "-" + (++n));
			t.setDaemon(true);
			return t;
		}
	}
}
