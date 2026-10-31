内置角色语音目录（离线随 mod，无需联网）
=============================================

本目录 assets/riichi_arise/sounds/voice/ 下按角色 id 分目录存放 6 名内置角色的 act 语音文件
（吃/碰/杠/立直/荣/自摸）。mod 在"个人设置"里对这 6 名角色标注「内置」，播放时直接从本资源读取，无需下载。

注意：报番语音（和牌结算时的役种/宝牌/段位念白，原 fan/ 子目录）已移出 mod，
改由独立资源包提供：仓库根目录 resourcepacks/riichi_arise_fan_voice/（构建任务 fanVoicePack 打成 zip）。
资源包路径同名覆盖即可，格式见该资源包内的 README.md。

每个角色目录需放置 6 条事件语音（mp3，命名必须完全一致），另可附带三麻拔北与流局听牌语音：

  <角色id>/act_chi.mp3    吃
  <角色id>/act_pon.mp3    碰
  <角色id>/act_kan.mp3    杠（暗杠/加杠/明杠）
  <角色id>/act_rich.mp3   立直
  <角色id>/act_ron.mp3    荣和
  <角色id>/act_tumo.mp3   自摸
  <角色id>/act_babei.mp3  拔北（三麻；内部 id babei，仅内置角色提供，不参与在线角色下载与齐全校验）
  <角色id>/act_tenpai.mp3 流局听牌（可选：缺失时静默跳过，不写日志）
  <角色id>/act_noten.mp3  流局未听牌（可选：同上）

流局时每位参战玩家只听到自己的听牌/未听语音；未入座的观战者按距离取最近一家（角色或玩家）的语音。

角色 id：
  linlang  琳琅
  yiji     一姬
  fuji     福姬
  bamuwei  八木唯
  akagi    赤木しげる
  washizu  鷲巣巌

来源：这些文件可由天凤 SE 站点（https://chen310.github.io/tenhou-se/）对应角色目录
（character/<id>/act_*.mp3）下载后放入，也可用任意天凤格式 mp3 语音（需与此命名一致）。

说明：目录当前为空（占位）。文件放入后重新打包 mod 即可生效；未放文件时该角色
语音静默跳过，不影响对局其他功能。
