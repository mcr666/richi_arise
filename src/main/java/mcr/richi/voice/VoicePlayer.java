package mcr.richi.voice;

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

	/** 一次播放请求：音频流 + 其代次 */
	private record Job(InputStream in, long gen) {
	}

	/** 播报某角色某事件的语音；无可用音频（文件缺失/未下载）时写日志并跳过 */
	public static void play(String charId, VoiceEvents ev) {
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
			PENDING.add(new Job(in, gen));
			PENDING.notifyAll();
		}
		startWorker();
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
				decodeAndWrite(job);
			} catch (Throwable e) {
				LogUtils.getLogger().warn("[voice] 播放失败: {}", e.toString());
			} finally {
				closeQuietly(job.in());
			}
		}
	}

	/** 解码 mp3 逐帧写入常驻音频行；被新播放取代（代次变化）时立即停止 */
	private static void decodeAndWrite(Job job) throws Exception {
		Bitstream bitstream = new Bitstream(new BufferedInputStream(job.in(), 8192));
		try {
			Decoder decoder = new Decoder();
			for (;;) {
				if (generation != job.gen())
					return;
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
			if (out != null && generation == job.gen())
				out.drain();
		} finally {
			try {
				bitstream.close();
			} catch (Throwable ignored) {
			}
		}
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
