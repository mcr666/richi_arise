package mcr.riichi_arise.net;

/**
 * 内网接入端口的运行配置（内存态，可由 /richi net 命令即时修改；默认关闭，命令显式开启）。
 * 出于安全默认绑定 127.0.0.1；写操作（join/打牌等）在设置了 token 后需在 hello 中携带。
 */
public final class RichiNetConfig {
	private RichiNetConfig() {
	}

	/** 默认监听端口 */
	public static int PORT = 45678;
	/** 绑定地址（默认本机回环；内网其它机器访问需改为 0.0.0.0） */
	public static String BIND = "127.0.0.1";
	/** 访问 token：空串 = 不鉴权（仅内网/本机使用）。非空时 hello 须携带相同 token，否则断开 */
	public static String TOKEN = "";
	/** 单行 JSON 最大长度（字节，防异常输入撑爆内存） */
	public static final int MAX_LINE_BYTES = 65536;
}
