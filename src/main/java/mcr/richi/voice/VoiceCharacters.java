package mcr.richi.voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 天凤式角色语音注册表。
 * <ul>
 * <li>离线内置角色（{@code offline=true}）：语音文件随 mod 打包于
 * {@code assets/riichi_arise/sounds/voice/<id>/act_<event>.mp3}，无需联网下载；文件由用户后续补入。</li>
 * <li>在线角色（{@code offline=false}）：语音资源取自天凤 SE 站点
 * {@code https://chen310.github.io/tenhou-se/character/<id>/act_<event>.mp3}，
 * 玩家在"个人设置"列表手动点击后由客户端下载到本地缓存。</li>
 * </ul>
 */
public final class VoiceCharacters {
	private VoiceCharacters() {
	}

	/** 一名角色：id（天凤角色目录名）、显示名、是否离线内置 */
	public record Character(String id, String displayName, boolean offline) {
	}

	/** 天凤 SE 站点根（在线角色的语音文件以此为前缀） */
	public static final String TENHOU_SE_BASE = "https://chen310.github.io/tenhou-se";

	/** 离线内置的 6 名角色 id（语音文件稍后由用户补入） */
	public static final List<String> OFFLINE_IDS = List.of(
			"linlang", "yuanxiao", "fuji", "bamuwei", "akagi", "washizu");

	private static final Map<String, Character> REG = new LinkedHashMap<>();

	private static void put(String id, String name, boolean offline) {
		REG.put(id, new Character(id, name, offline));
	}

	static {
		// —— 离线内置 6 名（语音稍后补文件） ——
		put("linlang", "琳琅", true);
		put("yuanxiao", "元宵", true);
		put("fuji", "福姬", true);
		put("bamuwei", "八木唯", true);
		put("akagi", "赤木茂", true);
		put("washizu", "鹫巢岩", true);

		// —— 在线角色（手动点击加载；id → 中文名，抄录自 tenhou-se 站点角色表） ——
		put("qiancangtou", "浅仓透", false);
		put("yiji", "一姬", false);
		put("yuanbanlin", "远坂凛", false);
		put("saber", "Saber", false);
		put("archer", "Archer", false);
		put("jing", "简", false);
		put("tongkouyuanxiang", "樋口圆香", false);
		put("fuwanxiaosi", "福丸小糸", false);
		put("shichuanchucai", "市川雏菜", false);
		put("jiantongying", "间桐樱", false);
		put("liyang", "璃央", false);
		put("bantianyinshi", "坂田银时", false);
		put("guixiaotailang", "桂小太郎", false);
		put("gaoshanjinzhu", "高杉晋助", false);
		put("banbenchenma", "坂本辰马", false);
		put("mila", "弥拉", false);
		put("beiluo", "北落", false);
		put("miya", "筱原米亚", false);
		put("jinwu", "金乌", false);
		put("tongren", "桐人", false);
		put("yasina", "亚丝娜", false);
		put("lifa", "莉法", false);
		put("shinai", "诗乃", false);
		put("erjietang", "二阶堂美树", false);
		put("jianai", "藤田佳奈", false);
		put("qianzhi", "三上千织", false);
		put("xiangyuanwu", "相原舞", false);
		put("fuzi", "抚子", false);
		put("jiutiao", "九条璃雨", false);
		put("zeniya", "泽尼娅", false);
		put("kawei", "卡维", false);
		put("sigongxiasheng", "四宫夏生", false);
		put("wangcilang", "汪次郎", false);
		put("yizhilaikong", "一之濑空", false);
		put("mingzhiyingshu", "明智英树", false);
		put("qingkuniang", "轻库娘", false);
		put("shala", "莎拉", false);
		put("erzhigonghua", "二之宫花", false);
		put("baishinainai", "白石奈奈", false);
		put("xiaoniaoyouchutian", "小鸟游雏田", false);
		put("wushilanyangcai", "五十岚阳菜", false);
		put("lianggongxingshu", "凉宫杏树", false);
		put("yuesefu", "约瑟夫", false);
		put("zhaitengzhi", "斋藤治", false);
		put("beijianshahezi", "北见纱和子", false);
		put("aiyin", "艾因", false);
		put("chutao", "雏桃", false);
		put("yuejianshan", "月见山", false);
		put("tengbenqiluo", "藤本绮罗", false);
		put("huiyeji", "辉夜姬", false);
		put("ruyuelian", "如月莲", false);
		put("shiyuanduihai", "石原碓海", false);
		put("ailisha", "艾丽莎", false);
		put("siqiqiansuili", "寺崎千穗理", false);
		put("gongyongxiao", "宫永咲", false);
		put("yuancunhe", "原村和", false);
		put("tianjiangyi", "天江衣", false);
		put("gongyongzhao", "宫永照", false);
		put("qixi", "七夕", false);
		put("shecan", "蛇喰梦子", false);
		put("zaoyinv", "早乙女芽亚里", false);
		put("shengzhimo", "生志摩妄", false);
		put("taocan", "桃喰绮罗莉", false);
		put("qihailinai", "七海礼奈", false);
		put("jichuanxiang", "姬川响", false);
		put("laien", "莱恩", false);
		put("senchuanlingzi", "森川绫子", false);
		put("longchuanxiayan", "泷川夏彦", false);
		put("xiyuansiyiyu", "西园寺一羽", false);
		put("xiaoyesiqiyu", "小野寺七羽", false);
		put("xiamier", "夏弥尔", false);
		put("sigonghuiye", "四宫辉夜", false);
		put("baiyinyuxing", "白银御行", false);
		put("zaobanai", "早坂爱", false);
		put("baiyingui", "白银圭", false);
		put("you", "柚", false);
		put("zekesi", "泽克斯", false);
		put("beiyuanlili", "北原莉莉", false);
		put("zhujingjiu", "竹井久", false);
		put("fulumeisuizi", "福路美穗子", false);
		put("xinzichong", "新子憧", false);
		put("yuanchengsilian", "园城寺怜", false);
		put("sigongdongshi", "四宫冬实", false);
		put("qingluan", "青鸾", false);
		put("ruyuecaiyin", "如月彩音", false);
		put("weilai", "未来", false);
		put("luluxiu", "鲁鲁修·兰佩洛基", false);
		put("cc", "C.C.", false);
		put("zhuque", "枢木朱雀", false);
		put("kalian", "红月卡莲", false);
		put("lanxing", "岚星", false);
		put("ling", "凌", false);
		put("dongchengxuanyin", "东城玄音", false);
		put("hanna", "汉娜", false);
		put("musa", "穆萨", false);
		put("yiliya", "伊莉雅", false);
		put("meiyou", "美游", false);
		put("xiaohei", "小黑", false);
		put("jier", "吉尔", false);
		put("yifu", "伊芙·克里斯特", false);
		put("yuanfeng", "袁枫", false);
		put("shalangbaizi", "砂狼白子", false);
		put("xiaoniaoyouxingye", "小鸟游星野", false);
		put("lubamoailu", "陆八魔爱露", false);
		put("qianhuangmuyue", "浅黄睦月", false);
		put("jiushi", "玖辻", false);
		put("xili", "希里", false);
		put("ju", "局", false);
		put("laiya", "莱娅", false);
		put("xinxiya", "辛西娅", false);
		put("nanfenghua", "南枫花", false);
		put("feiming", "非名", false);
		put("sataen", "萨塔恩", false);
		put("huayuqing", "花语青", false);
		put("huayubai", "花语白", false);
	}

	/** 全部角色（按注册顺序，内置在前） */
	public static List<Character> all() {
		return Collections.unmodifiableList(new ArrayList<>(REG.values()));
	}

	/** 按 id 取角色；未知 id 返回 null */
	public static Character byId(String id) {
		return REG.get(id);
	}

	/** 该 id 是否为已注册角色 */
	public static boolean exists(String id) {
		return REG.containsKey(id);
	}

	/** 该 id 是否离线内置 */
	public static boolean isOffline(String id) {
		Character c = REG.get(id);
		return c != null && c.offline();
	}

	/** 显示名（未知 id 回退为原串） */
	public static String displayName(String id) {
		Character c = REG.get(id);
		return c == null ? id : c.displayName();
	}

	/** 在线角色的天凤 SE 语音文件 URL（离线角色调用无意义，返回 null） */
	public static String actUrl(String id, VoiceEvents ev) {
		if (isOffline(id))
			return null;
		return TENHOU_SE_BASE + "/character/" + id + "/" + ev.act() + ".mp3";
	}
}
