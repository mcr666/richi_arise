package mcr.riichi_arise.voice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 天凤式角色语音名册。
 * <p>语音本身完全由已加载的资源包提供
 * （{@code assets/<命名空间>/sounds/voice/<id>/act_<event>.mp3}）。本类只维护「角色 id → 中文显示名」
 * 的对照表，供界面与日志使用；可选用角色列表由客户端扫描资源包得出（见 {@link VoiceCache#voiceIds()}），
 * 只要资源包提供音频，未随 mod 打包的角色同样可以选用。</p>
 */
public final class VoiceCharacters {
	private VoiceCharacters() {
	}

	/** 一名角色：id（资源包目录名）、显示名 */
	public record Character(String id, String displayName) {
	}

	/** 内置角色 id（act 语音随 mod 打包；报番兜底只借用这些角色） */
	public static final List<String> BUILTIN_IDS = List.of(
			"linlang", "yiji", "fuji", "bamuwei", "akagi", "washizu", "oraxolotl");

	/** 合法角色 id：小写字母/数字/下划线，1~32 位（服务端不知道客户端装了哪些资源包，只校验格式） */
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");

	private static final Map<String, Character> REG = new LinkedHashMap<>();

	private static void put(String id, String name) {
		REG.put(id, new Character(id, name));
	}

	static {
		// —— 内置角色（语音随 mod 打包） ——
		put("linlang", "琳琅");
		put("yiji", "一姬");
		put("fuji", "福姬");
		put("bamuwei", "八木唯");
		put("akagi", "赤木しげる");
		put("washizu", "鷲巣巌");
		put("oraxolotl", "桔螈");

		// —— 其他角色：仅提供中文显示名，语音由资源包提供 ——
		put("qiancangtou", "浅仓透");
		put("yuanbanlin", "远坂凛");
		put("saber", "Saber");
		put("archer", "Archer");
		put("jing", "简");
		put("tongkouyuanxiang", "樋口圆香");
		put("fuwanxiaosi", "福丸小糸");
		put("shichuanchucai", "市川雏菜");
		put("jiantongying", "间桐樱");
		put("liyang", "璃央");
		put("bantianyinshi", "坂田银时");
		put("guixiaotailang", "桂小太郎");
		put("gaoshanjinzhu", "高杉晋助");
		put("banbenchenma", "坂本辰马");
		put("mila", "弥拉");
		put("beiluo", "北落");
		put("miya", "筱原米亚");
		put("jinwu", "金乌");
		put("tongren", "桐人");
		put("yasina", "亚丝娜");
		put("lifa", "莉法");
		put("shinai", "诗乃");
		put("erjietang", "二阶堂美树");
		put("jianai", "藤田佳奈");
		put("qianzhi", "三上千织");
		put("xiangyuanwu", "相原舞");
		put("fuzi", "抚子");
		put("jiutiao", "九条璃雨");
		put("zeniya", "泽尼娅");
		put("kawei", "卡维");
		put("sigongxiasheng", "四宫夏生");
		put("wangcilang", "汪次郎");
		put("yizhilaikong", "一之濑空");
		put("mingzhiyingshu", "明智英树");
		put("qingkuniang", "轻库娘");
		put("shala", "莎拉");
		put("erzhigonghua", "二之宫花");
		put("baishinainai", "白石奈奈");
		put("xiaoniaoyouchutian", "小鸟游雏田");
		put("wushilanyangcai", "五十岚阳菜");
		put("lianggongxingshu", "凉宫杏树");
		put("yuesefu", "约瑟夫");
		put("zhaitengzhi", "斋藤治");
		put("beijianshahezi", "北见纱和子");
		put("aiyin", "艾因");
		put("chutao", "雏桃");
		put("yuejianshan", "月见山");
		put("tengbenqiluo", "藤本绮罗");
		put("huiyeji", "辉夜姬");
		put("ruyuelian", "如月莲");
		put("shiyuanduihai", "石原碓海");
		put("ailisha", "艾丽莎");
		put("siqiqiansuili", "寺崎千穗理");
		put("gongyongxiao", "宫永咲");
		put("yuancunhe", "原村和");
		put("tianjiangyi", "天江衣");
		put("gongyongzhao", "宫永照");
		put("qixi", "七夕");
		put("shecan", "蛇喰梦子");
		put("zaoyinv", "早乙女芽亚里");
		put("shengzhimo", "生志摩妄");
		put("taocan", "桃喰绮罗莉");
		put("qihailinai", "七海礼奈");
		put("jichuanxiang", "姬川响");
		put("laien", "莱恩");
		put("senchuanlingzi", "森川绫子");
		put("longchuanxiayan", "泷川夏彦");
		put("xiyuansiyiyu", "西园寺一羽");
		put("xiaoyesiqiyu", "小野寺七羽");
		put("xiamier", "夏弥尔");
		put("sigonghuiye", "四宫辉夜");
		put("baiyinyuxing", "白银御行");
		put("zaobanai", "早坂爱");
		put("baiyingui", "白银圭");
		put("you", "柚");
		put("zekesi", "泽克斯");
		put("beiyuanlili", "北原莉莉");
		put("zhujingjiu", "竹井久");
		put("fulumeisuizi", "福路美穗子");
		put("xinzichong", "新子憧");
		put("yuanchengsilian", "园城寺怜");
		put("sigongdongshi", "四宫冬实");
		put("qingluan", "青鸾");
		put("ruyuecaiyin", "如月彩音");
		put("weilai", "未来");
		put("luluxiu", "鲁鲁修·兰佩洛基");
		put("cc", "C.C.");
		put("zhuque", "枢木朱雀");
		put("kalian", "红月卡莲");
		put("lanxing", "岚星");
		put("ling", "凌");
		put("dongchengxuanyin", "东城玄音");
		put("hanna", "汉娜");
		put("musa", "穆萨");
		put("yiliya", "伊莉雅");
		put("meiyou", "美游");
		put("xiaohei", "小黑");
		put("jier", "吉尔");
		put("yifu", "伊芙·克里斯特");
		put("yuanfeng", "袁枫");
		put("shalangbaizi", "砂狼白子");
		put("xiaoniaoyouxingye", "小鸟游星野");
		put("lubamoailu", "陆八魔爱露");
		put("qianhuangmuyue", "浅黄睦月");
		put("jiushi", "玖辻");
		put("xili", "希里");
		put("ju", "局");
		put("laiya", "莱娅");
		put("xinxiya", "辛西娅");
		put("nanfenghua", "南枫花");
		put("feiming", "非名");
		put("sataen", "萨塔恩");
		put("huayuqing", "花语青");
		put("huayubai", "花语白");
	}

	/** 该 id 是否为内置角色 */
	public static boolean isBuiltin(String id) {
		return BUILTIN_IDS.contains(id);
	}

	/** id 是否合法（资源包目录名格式） */
	public static boolean isValidId(String id) {
		return id != null && ID_PATTERN.matcher(id).matches();
	}

	/** 显示名（未收录的 id 回退为原串） */
	public static String displayName(String id) {
		Character c = REG.get(id);
		return c == null ? id : c.displayName();
	}
}
