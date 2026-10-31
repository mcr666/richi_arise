Riichi Arise 报番语音资源包
============================

本包只提供「报番」语音（和牌结算时念役种名、宝牌张数、打点段位）。
对局中的吃/碰/杠/立直/荣/自摸喊话（act 语音）随 mod 打包，不在这里，也不受影响。


安装 / 生效
-----------
1. 把本目录（或构建出的 riichi_arise_fan_voice.zip）放进 游戏目录/resourcepacks/；
2. 游戏内「选项 → 资源包」启用它（放右侧）；
3. 已经在游戏里时，按 F3+T 重载资源即可生效，不必重启。

构建 zip：在项目根目录执行  gradlew fanVoicePack
产物：build/resourcepacks/riichi_arise_fan_voice.zip（gradlew build 也会顺带生成）


目录结构
--------
  assets/<命名空间>/sounds/voice/<角色id>/fan/<音键>.mp3

mod 先按主命名空间 riichi_arise 查找（本包用的就是它，可直接覆盖 mod 自带资源）；
找不到时会在所有已启用资源包里按「角色id + 音键」兜底查找，所以用自己的命名空间也可以。


角色 id
-------
内置角色：linlang(琳琅)  yiji(一姬)  fuji(福姬)  bamuwei(八木唯)  akagi(赤木茂)  washizu(鹫巢岩)
也可以给其他 id（如在线角色）补一份报番语音：目录名与个人设置里保存的角色 id 一致即可。


音键（文件名必须完全一致，全部为 .mp3）
--------------------------------------
役种：
  tanyao pinfu ipeiko ryanpeikou sanshokudoujun sanshokudoukou ittsuu toitoi
  sanankou sankantsu sukantsu shousangen chiitoi chanta junchan honroutou
  chinrutou honitsu chinitsu tsuuiisou ryuuiisou riichi daburii tsumo
  chankan rinshan haitei houtei haku hatsu chun ippatsu
  ton nan shaa pei                      （自风/场风：直接报方位）
  tenhou chiho kokushi kokushi13 suankou suankoutanki daisangen
  shousuushi daisushi chuuren chuurenpure

宝牌计数（表/里/红宝牌通用，按张数分开报）：
  dora dora2 dora3 dora4 dora5 dora6 dora7 dora8 dora9 dora10 dora11 dora12 dora13

打点段位（结算点数显现时播）：
  mangan tiaoman beiman sanbeiman leijiyiman yiman1 yiman2 yiman3 yiman4 yiman5 yiman6


行为说明
--------
- 某个音键缺文件：该条静默跳过，其文字随相邻语音一起显现，报番流程不受影响；
- 一个角色的报番语音全都缺失：结算界面回退为固定节奏逐条显示（并在日志里提示需要本资源包）；
- 角色选的是在线语音、但本包没有对应目录：同样按「缺失」处理，只影响报番念白，act 语音照常。
