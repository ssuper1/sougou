import java.io.File;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlVisitor;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;

/** Edits only the outer manifest; LSPatch's nested original APK remains byte-for-byte intact. */
public final class PatchSettings {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String ACTIVITY = "com.qoder.sogousym.EmbeddedSettingsActivity";
    private static final String PROVIDER = "com.qoder.sogousym.ConfigProvider";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: PatchSettings patched.apk");
        }
        Path inputPath = new File(args[0]).toPath();
        Path outputPath = inputPath.resolveSibling(inputPath.getFileName() + ".settings.tmp.apk");
        // A standard ZIP reader accepts LSPatch's deliberately overlapping nested entries.
        try (ZipFile apk = new ZipFile(inputPath.toFile())) {
            byte[] manifest;
            try (InputStream input = apk.getInputStream(apk.getEntry("AndroidManifest.xml"))) {
                manifest = input.readAllBytes();
            }
            AxmlWriter writer = new AxmlWriter();
            boolean[] foundApplication = {false};
            new AxmlReader(manifest).accept(new AxmlVisitor(writer) {
                @Override
                public NodeVisitor child(String namespace, String name) {
                    NodeVisitor root = super.child(namespace, name);
                    return new NodeVisitor(root) {
                        @Override
                        public NodeVisitor child(String ns, String tag) {
                            NodeVisitor node = super.child(ns, tag);
                            if (!"application".equals(tag)) {
                                return node;
                            }
                            foundApplication[0] = true;
                            return new NodeVisitor(node) {
                                @Override
                                public NodeVisitor child(String childNs, String childTag) {
                                    return new NodeVisitor(super.child(childNs, childTag)) {
                                        @Override
                                        public void attr(String attrNs, String attrName, int id, int type, Object value) {
                                            if ("name".equals(attrName)
                                                    && (ACTIVITY.equals(value) || PROVIDER.equals(value))) {
                                                throw new IllegalStateException("Settings components already exist");
                                            }
                                            super.attr(attrNs, attrName, id, type, value);
                                        }
                                    };
                                }

                                @Override
                                public void end() {
                                    addSettings(node);
                                    super.end();
                                }
                            };
                        }
                    };
                }
            });
            if (!foundApplication[0]) {
                throw new IllegalStateException("APK has no application element");
            }
            byte[] newManifest = writer.toByteArray();
            try (CountingOutputStream counted = new CountingOutputStream(Files.newOutputStream(outputPath));
                    ZipOutputStream output = new ZipOutputStream(counted)) {
                Enumeration<? extends ZipEntry> entries = apk.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry original = entries.nextElement();
                    String name = original.getName();
                    if (name.matches("(?i)META-INF/.*\\.(SF|RSA|DSA|EC)")
                            || "META-INF/MANIFEST.MF".equalsIgnoreCase(name)) {
                        continue;
                    }
                    ZipEntry entry = new ZipEntry(name);
                    entry.setMethod(original.getMethod());
                    boolean isManifest = "AndroidManifest.xml".equals(name);
                    if (entry.getMethod() == ZipEntry.STORED) {
                        CRC32 crc = new CRC32();
                        if (isManifest) { crc.update(newManifest); }
                        entry.setSize(isManifest ? newManifest.length : original.getSize());
                        entry.setCrc(isManifest ? crc.getValue() : original.getCrc());
                        int alignment = name.endsWith(".so") || "assets/lspatch/origin.apk".equals(name) ? 4096 : 4;
                        long start = counted.count + 30 + name.getBytes(StandardCharsets.UTF_8).length;
                        int padding = (int) ((alignment - start % alignment) % alignment);
                        if (padding > 0) {
                            if (padding < 4) { padding += alignment; }
                            byte[] extra = new byte[padding];
                            extra[0] = (byte) 0x35;
                            extra[1] = (byte) 0xd9;
                            extra[2] = (byte) (padding - 4);
                            extra[3] = (byte) ((padding - 4) >>> 8);
                            entry.setExtra(extra);
                        }
                    }
                    output.putNextEntry(entry);
                    if (isManifest) {
                        output.write(newManifest);
                    } else {
                        try (InputStream input = apk.getInputStream(original)) {
                            input.transferTo(output);
                        }
                    }
                    output.closeEntry();
                }
            }
        }
        Files.move(outputPath, inputPath, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Added embedded settings activity and private config provider");
    }

    private static void addSettings(NodeVisitor application) {
        NodeVisitor activity = application.child(null, "activity");
        string(activity, "name", 0x01010003, ACTIVITY);
        string(activity, "label", 0x01010001, "\u641c\u72d7\u7b26\u53f7");
        string(activity, "process", 0x01010011, ":sogousym");
        string(activity, "taskAffinity", 0x01010012, "com.sohu.inputmethod.sogou.sogousym");
        activity.attr(ANDROID, "exported", 0x01010010, NodeVisitor.TYPE_INT_BOOLEAN, Boolean.TRUE);
        activity.attr(ANDROID, "screenOrientation", 0x0101001e, NodeVisitor.TYPE_FIRST_INT, 10);
        activity.attr(ANDROID, "configChanges", 0x0101001f, NodeVisitor.TYPE_INT_HEX, 0x1fa0);
        NodeVisitor filter = activity.child(null, "intent-filter");
        NodeVisitor action = filter.child(null, "action");
        string(action, "name", 0x01010003, "android.intent.action.MAIN");
        action.end();
        NodeVisitor category = filter.child(null, "category");
        string(category, "name", 0x01010003, "android.intent.category.LAUNCHER");
        category.end();
        filter.end();
        activity.end();

        NodeVisitor provider = application.child(null, "provider");
        string(provider, "name", 0x01010003, PROVIDER);
        string(provider, "authorities", 0x01010018, "com.sohu.inputmethod.sogou.sogousym.config");
        string(provider, "process", 0x01010011, ":sogousym");
        provider.attr(ANDROID, "exported", 0x01010010, NodeVisitor.TYPE_INT_BOOLEAN, Boolean.FALSE);
        provider.end();
    }

    private static void string(NodeVisitor node, String name, int id, String value) {
        node.attr(ANDROID, name, id, NodeVisitor.TYPE_STRING, value);
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count;

        CountingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws java.io.IOException {
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] value, int offset, int length) throws java.io.IOException {
            out.write(value, offset, length);
            count += length;
        }
    }
}
