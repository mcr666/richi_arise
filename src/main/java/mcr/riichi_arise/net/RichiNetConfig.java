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
	/** 镜面座位（外部牌谱驱动，无本地操作）自动摸切延迟（毫秒）：外部程序未及时推送下一步时，
	 *  牌桌按此延时自动摸切推进，防止外部桥断开/卡住时整桌卡死。0 = 不自动摸切（完全被动等待） */
	public static volatile int MIRROR_AUTO_DISCARD_MS = 4000;
	/** 控制面板发起连接时记录的事件来源（接管来源显示；桥接器实际接入时由 join 的 source 字段覆盖） */
	public static final String SOURCE_CLIENT = "richi_arise:client";
	/** 单行 JSON 最大长度（字节，防异常输入撑爆内存） */
	public static final int MAX_LINE_BYTES = 65536;
}
