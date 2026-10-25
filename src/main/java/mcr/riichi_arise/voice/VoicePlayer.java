package mcr.riichi_arise.voice;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import com.mojang.logging.LogUtils;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * 内置 mp3 播放器（客户端）。
 * <p>
 * 用 jlayer（mp3 解码库，随 mod 打包）直接解码 mp3 → PCM，写入常驻的 javax.sound SourceDataLine 播放。
 * 绕开 MC 原生 SoundEngine 仅支持 .ogg 的限制，也规避 javax.sound.sampled 在 mod 类加载器下
 * SPI 未必被发现的隐患。
 * </p>
 * <p>
 * 与"每次播放新开线程 + 新开音频行"的做法不同，本播放器消除首次播放的明显延迟：
 * <ol>
 * <li>{@link #warmup()} 在客户端启动时于后台线程预先触碰音频子系统（SPI/混音器枚举）、打开常驻音频行、
 * 并解码一段内置语音以预热 jlayer 解码类；</li>
 * <li>播放线程常驻（daemon），音频行跨次复用，播放时只需解码 + 写数据。</li>
 * </ol>
 * 新语音事件会打断当前播放（generation 递增，播放循环在帧边界立即退出）；队列只保留最新一条，避免积压补播。
 * </p>
 */
public final class VoicePlayer {
	private VoicePlayer() {
	}

	/** 待播队列（最多保留最新一条） */
	private static final ArrayDeque<Job> PENDING = new ArrayDeque<>();
	/** 常驻播放线程 */
	private static volatile Thread worker;
	/** 播放代次：每次 play()/stopAll() 递增，播放循环据此判断自己是否已被取代 */
	private static volatile long generation;
	/** 常驻音频行与格式（复用，避免每次播放重新初始化音频设备） */
	private static SourceDataLine line;
	private static AudioFormat lineFormat;
	/** 主播放线程是否正在播一条语音（isBusy 判定用） */
	private static volatile boolean playing;
	/** 并行播放中的语音条数（多家和牌语音同时播） */
	private static final java.util.concurrent.atomic.AtomicInteger CONCURRENT =
			new java.util.concurrent.atomic.AtomicInteger();
	/** 报番代次：清掉报番语音时递增，只中止报番类任务（不影响正在播的荣/自摸等 act 语音） */
	private static volatile long fanToken;

	/** 一次播放请求：音频流 + 其代次 + 是否报番类 + 报番代次 + 播完回调（被新语音打断则不回调；可为 null） */
	private record Job(InputStream in, long gen, boolean fan, long fanToken, Runnable onDone) {
	}

	/** 播报某角色某事件的语音；无可用音频（文件缺失/未下载）时写日志并跳过 */
	public static void play(String charId, VoiceEvents ev) {
		play(charId, ev, null);
	}

	/** 同上；语音自然播完后回调 onDone（播放线程调用） */
	public static void play(String charId, VoiceEvents ev, Runnable onDone) {
		if (charId == null || charId.isEmpty())
			return;
		InputStream in = VoiceCache.open(charId, ev);
		if (in == null) {
			LogUtils.getLogger().warn("[voice] 无可用语音文件: {} {}（离线内置缺文件或在线未下载成功）",
					charId, ev.act());
			return;
		}
		long gen = ++generation; // 先递增：当前播放中的语音随即失效
		synchronized (PENDING) {
			while (!PENDING.isEmpty())
				closeQuietly(PENDING.poll().in()); // 丢弃未播的积压
			PENDING.add(new Job(in, gen, false, 0, onDone));
			PENDING.notifyAll();
		}
		startWorker();
	}

	/**
	 * 报番语音：追加到播放队列尾部（不丢积压、不打断当前播报），按入队顺序接续播放。
	 * 音频缺失时静默跳过。随后的 {@link #play} 会递增代次并清空积压，即新 act 语音/清场会打断报番。
	 */
	public static void enqueue(String charId, String fanKey) {
		enqueue(charId, fanKey, null);
	}

	/** 同上；该条语音自然播完后回调 onDone（播放线程调用，被新语音打断则不回调） */
	public static void enqueue(String charId, String fanKey, Runnable onDone) {
		enqueueOrSkip(charId, fanKey, onDone);
	}

	/** 入队一条报番语音；音频缺失时返回 false（由调用方决定如何补齐进度） */
	public static boolean enqueueOrSkip(String charId, String fanKey, Runnable onDone) {
		if (charId == null || charId.isEmpty() || fanKey == null || fanKey.isEmpty())
			return false;
		InputStream in = VoiceCache.openFan(charId, fanKey);
		if (in == null)
			return false;
		synchronized (PENDING) {
			PENDING.add(new Job(in, generation, true, fanToken, onDone));
			PENDING.notifyAll();
		}
		startWorker();
		return true;
	}

	/**
	 * 清掉报番类语音：丢弃排队中的报番任务，并让正在播的那一条报番立即中止；
	 * 正在播的荣/自摸等 act 语音不受影响（播放循环只对报番任务校验 {@link #fanToken}）。
	 * 用于新一局报番开始前、结算界面关闭时清理残留（末尾宝牌/段位语音），避免它们排到番名前先响。
	 */
	public static void clearFan() {
		fanToken++;
		synchronized (PENDING) {
			java.util.Iterator<Job> it = PENDING.iterator();
			while (it.hasNext()) {
				Job j = it.next();
				if (j.fan()) {
					it.remove();
					closeQuietly(j.in());
				}
			}
		}
	}

	/**
	 * 与主播放并行播一条语音（多家荣和的"荣"语音同时播报）：独占一条音频行，由系统混音器叠加；
	 * 不打断当前播放，但之后 play()/stopAll() 递增代次时立即中止。无音频时静默跳过。
	 */
	public static void playConcurrent(String charId, VoiceEvents ev) {
		if (charId == null || charId.isEmpty())
			return;
		InputStream in = VoiceCache.open(charId, ev);
		if (in == null) {
			LogUtils.getLogger().warn("[voice] 无可用语音文件: {} {}（离线内置缺文件或在线未下载成功）",
					charId, ev.act());
			return;
		}
		long gen = generation;
		CONCURRENT.incrementAndGet();
		Thread t = new Thread(() -> {
			try (InputStream src = in) {
				playOwnLine(src, gen);
			} catch (Throwable e) {
				LogUtils.getLogger().warn("[voice] 并行播放失败: {}", e.toString());
			} finally {
				CONCURRENT.decrementAndGet();
			}
		}, "riichi-voice-concurrent");
		t.setDaemon(true);
		t.start();
	}

	/** 是否还有语音正在播/待播（结算界面延迟到和牌语音播完再打开用） */
	public static boolean isBusy() {
		if (playing || CONCURRENT.get() > 0)
			return true;
		synchronized (PENDING) {
			return !PENDING.isEmpty();
		}
	}

	/** 在独立音频行上解码播放（供并行播放；代次变化即中止） */
	private static void playOwnLine(InputStream in, long gen) throws Exception {
		Bitstream bitstream = new Bitstream(new BufferedInputStream(in, 8192));
		SourceDataLine out = null;
		try {
			Decoder decoder = new Decoder();
			for (;;) {
				if (generation != gen)
					return;
				Header header = bitstream.readFrame();
				if (header == null)
					break;
				SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
				if (out == null) {
					float freq = decoder.getOutputFrequency() > 0 ? decoder.getOutputFrequency() : 44100;
					int channels = decoder.getOutputChannels() > 0 ? decoder.getOutputChannels() : 2;
					AudioFormat fmt = new AudioFormat(freq, 16, channels, true, false);
					out = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
					out.open(fmt);
					out.start();
				}
				short[] samples = output.getBuffer();
				int len = output.getBufferLength();
				if (len > 0)
					out.write(toBytes(samples, len), 0, len * 2);
				bitstream.closeFrame();
			}
			if (out != null && generation == gen)
				out.drain();
		} finally {
			if (out != null) {
				try {
					out.stop();
					out.flush();
					out.close();
				} catch (Throwable ignored) {
				}
			}
			try {
				bitstream.close();
			} catch (Throwable ignored) {
			}
		}
	}

	/** 立即停止当前播放（离开牌桌/清场时用） */
	public static void stopAll() {
		generation++;
		synchronized (PENDING) {
			while (!PENDING.isEmpty())
				closeQuietly(PENDING.poll().in());
		}
	}

	/**
	 * 预热（客户端启动时调用，立即返回）：后台完成音频子系统初始化与 jlayer 类加载，
	 * 使首次真实播放不再承担这几百毫秒的一次性开销。
	 */
	public static void warmup() {
		Thread t = new Thread(VoicePlayer::warmupTask, "riichi-voice-warmup");
		t.setDaemon(true);
		t.start();
	}

	private static void warmupTask() {
		try {
			// 1) 用一段内置语音的首帧拿到真实采样格式（同时预热 jlayer 解码类与资源读取）
			AudioFormat fmt = null;
			InputStream in = null;
			for (String id : VoiceCharacters.OFFLINE_IDS) {
				in = VoiceCache.open(id, VoiceEvents.CHI);
				if (in != null)
					break;
			}
			if (in != null) {
				try (InputStream src = in) {
					Bitstream bs = new Bitstream(new BufferedInputStream(src, 8192));
					try {
						Header header = bs.readFrame();
						if (header != null) {
							Decoder d = new Decoder();
							d.decodeFrame(header, bs);
							float freq = d.getOutputFrequency();
							int ch = d.getOutputChannels();
							if (freq > 0 && ch > 0)
								fmt = new AudioFormat(freq, 16, ch, true, false);
						}
					} finally {
						try {
							bs.close();
						} catch (Throwable ignored) {
						}
					}
				}
			}
			if (fmt == null)
				fmt = new AudioFormat(44100f, 16, 2, true, false);
			// 2) 打开常驻音频行（首次打开会初始化混音器，开销最大）
			SourceDataLine l = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
			l.open(fmt);
			l.start();
			synchronized (VoicePlayer.class) {
				line = l;
				lineFormat = fmt;
			}
		} catch (Throwable e) {
			LogUtils.getLogger().warn("[voice] 音频预热失败（首次播放可能略有延迟）: {}", e.toString());
		}
	}

	private static void startWorker() {
		Thread t = worker;
		if (t != null && t.isAlive())
			return;
		synchronized (VoicePlayer.class) {
			if (worker != null && worker.isAlive())
				return;
			worker = new Thread(VoicePlayer::loop, "riichi-voice-player");
			worker.setDaemon(true);
			worker.start();
		}
	}

	private static void loop() {
		for (;;) {
			Job job;
			synchronized (PENDING) {
				while (PENDING.isEmpty()) {
					try {
						PENDING.wait();
					} catch (InterruptedException e) {
						return;
					}
				}
				job = PENDING.poll();
			}
			try {
				playing = true;
				if (decodeAndWrite(job) && job.onDone() != null)
					job.onDone().run();
			} catch (Throwable e) {
				LogUtils.getLogger().warn("[voice] 播放失败: {}", e.toString());
			} finally {
				playing = false;
				closeQuietly(job.in());
			}
		}
	}

	/** 解码 mp3 逐帧写入常驻音频行；被新播放取代（代次变化）或报番被清掉时中断。完整播完返回 true（供完成回调） */
	private static boolean decodeAndWrite(Job job) throws Exception {
		Bitstream bitstream = new Bitstream(new BufferedInputStream(job.in(), 8192));
		try {
			Decoder decoder = new Decoder();
			for (;;) {
				if (cancelled(job))
					return false;
				Header header = bitstream.readFrame();
				if (header == null)
					break;
				SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
				SourceDataLine out = lineFor(decoder);
				if (out != null) {
					short[] samples = output.getBuffer();
					int len = output.getBufferLength();
					if (len > 0)
						out.write(toBytes(samples, len), 0, len * 2);
				}
				bitstream.closeFrame();
			}
			SourceDataLine out = line;
			if (out != null && !cancelled(job))
				out.drain();
			return !cancelled(job);
		} finally {
			try {
				bitstream.close();
			} catch (Throwable ignored) {
			}
		}
	}

	/** 该任务是否已被取代：act 语音看播放代次，报番语音另看报番代次（clearFan 可单独中止报番） */
	private static boolean cancelled(Job job) {
		return generation != job.gen() || (job.fan() && fanToken != job.fanToken());
	}

	/** 取与解码输出格式匹配的音频行：常驻行格式一致则复用，否则重开（格式变化极少见） */
	private static SourceDataLine lineFor(Decoder decoder) throws Exception {
		float freq = decoder.getOutputFrequency();
		int channels = decoder.getOutputChannels();
		if (freq <= 0)
			freq = 44100;
		if (channels <= 0)
			channels = 2;
		AudioFormat fmt = new AudioFormat(freq, 16, channels, true, false);
		SourceDataLine cur = line;
		if (cur != null && cur.isOpen() && sameFormat(lineFormat, fmt))
			return cur;
		synchronized (VoicePlayer.class) {
			cur = line;
			if (cur != null && cur.isOpen() && sameFormat(lineFormat, fmt))
				return cur;
			closeLine();
			SourceDataLine fresh = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
			fresh.open(fmt);
			fresh.start();
			line = fresh;
			lineFormat = fmt;
			return fresh;
		}
	}

	private static boolean sameFormat(AudioFormat a, AudioFormat b) {
		return a != null && b != null
				&& a.getSampleRate() == b.getSampleRate()
				&& a.getSampleSizeInBits() == b.getSampleSizeInBits()
				&& a.getChannels() == b.getChannels();
	}

	private static void closeLine() {
		SourceDataLine l = line;
		line = null;
		if (l == null)
			return;
		try {
			l.stop();
			l.flush();
			l.close();
		} catch (Throwable ignored) {
		}
	}

	private static void closeQuietly(InputStream in) {
		if (in == null)
			return;
		try {
			in.close();
		} catch (Throwable ignored) {
		}
	}

	/** short[]（PCM 小端有符号）→ byte[]，供 SourceDataLine.write */
	private static byte[] toBytes(short[] s, int len) {
		byte[] b = new byte[len * 2];
		for (int i = 0; i < len; i++) {
			short v = s[i];
			b[i * 2] = (byte) (v & 0xFF);
			b[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
		}
		return b;
	}
}
