import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Build helper: emit an access-transformer-equivalent override class so that
 * plain javac (which does not apply accesstransformer.cfg) can verify the mod sources.
 * Only net.minecraft.client.multiplayer.ClientPacketListener#playerInfoMap is made public,
 * matching the AT line in META-INF/accesstransformer.cfg.
 */
public class AtPatch {
	public static void main(String[] a) throws Exception {
		String jar = a[0];
		String outDir = a[1];
		String target = "net/minecraft/client/multiplayer/ClientPacketListener.class";
		try (ZipFile zf = new ZipFile(jar)) {
			ZipEntry e = zf.getEntry(target);
			if (e == null) { System.out.println("NOT_FOUND"); return; }
			byte[] data;
			try (InputStream is = zf.getInputStream(e)) { data = is.readAllBytes(); }
			ClassReader cr = new ClassReader(data);
			ClassWriter cw = new ClassWriter(0);
			ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
				@Override
				public FieldVisitor visitField(int acc, String name, String desc, String sig, Object val) {
					if ("playerInfoMap".equals(name)) {
						acc = (acc & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC;
					}
					return super.visitField(acc, name, desc, sig, val);
				}
			};
			cr.accept(cv, 0);
			Path out = Paths.get(outDir, target);
			Files.createDirectories(out.getParent());
			Files.write(out, cw.toByteArray());
			System.out.println("PATCHED " + out);
		}
	}
}
