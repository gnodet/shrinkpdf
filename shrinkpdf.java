///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS org.apache.pdfbox:pdfbox:2.0.22
//DEPS org.apache.pdfbox:pdfbox-debugger:2.0.22
//DEPS org.apache.pdfbox:jbig2-imageio:3.0.3
//DEPS com.github.jai-imageio:jai-imageio-core:1.4.0
//DEPS com.github.jai-imageio:jai-imageio-jpeg2000:1.4.0
//DEPS com.twelvemonkeys.imageio:imageio-jpeg:3.6.2
//DEPS net.sourceforge.tess4j:tess4j:4.5.4
//DEPS org.slf4j:slf4j-simple:1.7.30
//DEPS org.slf4j:log4j-over-slf4j:1.7.30
//DEPS org.jline:jline:3.19.0

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.recognition.software.jdeskew.ImageDeskew;
import com.recognition.software.jdeskew.ImageUtil;
import org.apache.log4j.PropertyConfigurator;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.Display;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "shrinkpdf", mixinStandardHelpOptions = true, version = "shrinkpdf 0.1",
        description = "shrinkpdf made with jbang")
class shrinkpdf implements Callable<Integer> {

    @Option(names = "--resolution", defaultValue = "200", description = "The resolution to use when processing images")
    private int resolution;

    @Option(names = "--quality", defaultValue = "0.6", description = "The resolution to use when processing images")
    private float quality;

//    @Option(names = "--kbpp", defaultValue = "500", description = "The size per page (in kilobytes) under which shrinkpdf will ignore the file")
//    private int kbpp;

//    @Option(names = "--noocr", defaultValue = "false", description = "Disable OCR processing")
//    private boolean noocr;

    @Option(names = "--forceocr", defaultValue = "false", description = "Force OCR processing")
    private boolean forceocr;

    @Option(names = "--nodeskew", defaultValue = "false", description = "Disable deskewing")
    private boolean nodeskew;

    @Option(names = "--debug", defaultValue = "false", description = "Debug mode")
    private boolean debug;

    @Parameters(description = "List of files to process", arity = "1..n")
    private Path[] files;

    public static void main(String... args) {
        final Properties props = new Properties();
        props.setProperty("log4j.rootLogger", "ERROR, A1");
        props.setProperty("log4j.appender.A1", "org.apache.log4j.ConsoleAppender");
        props.setProperty("log4j.appender.A1.layout", "org.apache.log4j.TTCCLayout");
        PropertyConfigurator.configure(props);

        int exitCode = new CommandLine(new shrinkpdf()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        try (Terminal terminal = TerminalBuilder.terminal()) {
            display = new Display(terminal, false);
            terminal.handle(Terminal.Signal.WINCH, s -> size = terminal.getSize());
            size = terminal.getSize();
            Stream.of(files).parallel()
                    .map(Path::toAbsolutePath)
                    .map(DocumentProcessor::new)
                    .forEach(DocumentProcessor::process);
            display.clear();
            int errors = 0;
            for (Map.Entry<Path, Object> result : results.entrySet()) {
                if (result != null) {
                    Object v = result.getValue();
                    if (v instanceof Throwable) {
                        System.out.println(result.getKey() + " => " + v);
                        ((Throwable) v).printStackTrace();
                        errors++;
                    }
                }
            }
            System.out.println("Processed " + results.size() + " files with " + errors + " errors");
        }
        return 0;
    }

    Size size;
    Display display;
    Map<Path, AttributedString> processing = new LinkedHashMap<>();
    Map<Path, Object> results = new LinkedHashMap<>();

    public synchronized void progress(Path file, AttributedString state) {
        processing.put(file, state);
        redraw();
    }

    public synchronized void finish(Path file, Object result) {
        processing.remove(file);
        results.put(file, result);
        redraw();
    }

    void redraw() {
        display.resize(size.getRows(), size.getColumns());
        display.update(new ArrayList<>(processing.values()), 0);
    }

    class DocumentProcessor {
        final Path file;
        PDDocument document;
        Path temp;
        Exception error;
        AtomicInteger pagesDone = new AtomicInteger();
        List<PDDocument> opened = new ArrayList<>();

        DocumentProcessor(Path file) {
            this.file = file;
        }

        public void process() {
            try {
                // Load document
                update();
                document = PDDocument.load(file.toFile());
                String ocr = document.getDocumentInformation().getCustomMetadataValue("ocr");
                if (ocr == null || forceocr) {
                    document.setAllSecurityToBeRemoved(true);
                    temp = Files.createTempDirectory("shrinkpdf-");
                    // Process pages
                    IntStream.range(0, document.getNumberOfPages())
                            .mapToObj(PageProcessor::new).forEach(PageProcessor::process);
                    // Save
                    if (error == null) {
                        Path out = temp.resolve(file.getFileName().toString() + ".tmp");
                        document.getDocumentInformation().setCustomMetadataValue("ocr", "true");
                        document.save(out.toFile());
                        document.close();
                        trashAndReplace(out, file);
                    }
                }
            } catch (Exception e) {
                error = e;
            } finally {
                for (PDDocument doc : opened) {
                    try {
                        doc.close();
                    } catch (IOException e) {
                    }
                }
                if (!debug) {
                    try {
                        if (temp != null && Files.isDirectory(temp)) {
                            for (Path file : Files.newDirectoryStream(temp)) {
                                Files.delete(file);
                            }
                            Files.delete(temp);
                        }
                    } catch (IOException e) {
                        // ignore
                    }
                }
                finish(file, error);
            }
        }

        void pageDone() {
            pagesDone.incrementAndGet();
            update();
        }

        void update() {
            AttributedStringBuilder sb = new AttributedStringBuilder(80);
            String s = file.getFileName().toString();
            sb.append(s, 0, Math.min(s.length(), 30));
            while (sb.length() < 30) {
                sb.append(' ');
            }
            if (document != null) {
                int c = pagesDone.get();
                int m = document.getNumberOfPages();
                int i = (c * 40) / m;
                sb.append('[');
                for (int k = 0; k < 40; k++) {
                    sb.append(k <= i ? '▮' : '▯');
                }
                sb.append("] ");
                sb.append(Integer.toString(c));
                sb.append("/");
                sb.append(Integer.toString(m));
                sb.append(" pages");
            } else {
                sb.append('[');
                for (int k = 0; k < 40; k++) {
                    sb.append('▯');
                }
                sb.append("] ");
            }
            progress(file, sb.toAttributedString());
        }

        class PageProcessor {
            final int pageIndex;
            final PDPage page;

            PageProcessor(int pageIndex) {
                this.pageIndex = pageIndex;
                this.page = document.getPage(pageIndex);
            }

            void process() {
                if (error != null) {
                    return;
                }
                try {
                    // Deskew
                    if (!nodeskew) {
                        processImages("", page.getResources(), this::deskew);
                    }
                    // OCR
                    PDDocument overlay = ocr();
                    opened.add(overlay);
                    // Overlay
                    overlay(overlay.getPage(0));
                    // Compress
                    PrintImageLocations locs = new PrintImageLocations();
                    locs.processPage(page);
                    processImages("", page.getResources(), (n, x) -> compress(document, x, locs.scale.get(n)));
                } catch (Exception t) {
                    error = t;
                } finally {
                    pageDone();
                }

            }

            PDImageXObject deskew(String location, PDImageXObject xObject) throws IOException {
                if (xObject.getWidth() >= 500 && xObject.getWidth() >= 500) {
                    BufferedImage bi = xObject.getImage();
                    double angle = new ImageDeskew(bi).getSkewAngle();
                    if (Math.abs(angle) > 0.05d) {
                        bi = ImageUtil.rotate(bi, -angle, bi.getWidth() / 2, bi.getHeight() / 2);
                        return JPEGFactory.createFromImage(document, bi, 0.85f, 300);
                    }
                }
                return xObject;
            }

            PDDocument ocr() throws IOException, InterruptedException {
                PDFRenderer renderer = new FilteringPDFRenderer(document, false, true, false);
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, 300, ImageType.GRAY);

                Path page = temp.resolve("page" + pageIndex + ".pgm");
                ImageIO.write(image, "pnm", page.toFile());

                ProcessBuilder builder = new ProcessBuilder(
                        "tesseract",
                        "page" + pageIndex + ".pgm", "page" + pageIndex,
                        "-l", "eng+fra", "pdf");
                builder.environment().put("OMP_THREAD_LIMIT", "1");
                builder.directory(temp.toFile());
                Process process = builder.start();
                process.waitFor();

                Path overlay = temp.resolve("page" + pageIndex + ".pdf");
                if (!Files.exists(overlay)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    PrintWriter pw = new PrintWriter(baos, true, StandardCharsets.UTF_8);
                    pw.println("=== Command ===");
                    pw.printf("Directory: %s%nArguments:%n", temp);
                    builder.command().forEach(pw::println);
                    pw.println("=== Error ===");
                    pw.flush();
                    IOUtils.copy(process.getErrorStream(), baos);
                    throw new IOException("Unable to run tesseract\n" + baos.toString());
                }
                PDDocument doc = PDDocument.load(overlay.toFile());
                return doc;
            }

            void overlay(PDPage overlay) throws IOException {
                COSDictionary pageDictionary = page.getCOSObject();
                COSStream stream = document.getDocument().createCOSStream();
                try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
                    IOUtils.copy(page.getContents(), os);
                    os.write('\n');

                    PDFStreamParser parser = new PDFStreamParser(overlay);
                    ContentStreamWriter writer = new ContentStreamWriter(os);

                    writer.writeToken(Operator.getOperator(OperatorName.SAVE));
                    writer.writeToken(new COSFloat(page.getMediaBox().getWidth() / overlay.getMediaBox().getWidth()));
                    writer.writeToken(new COSFloat(0.0f));
                    writer.writeToken(new COSFloat(0.0f));
                    writer.writeToken(new COSFloat(page.getMediaBox().getHeight() / overlay.getMediaBox().getHeight()));
                    writer.writeToken(new COSFloat(0.0f));
                    writer.writeToken(new COSFloat(0.0f));
                    writer.writeToken(Operator.getOperator(OperatorName.CONCAT));

                    List<Object> tokens = new ArrayList<>();
                    Object token;
                    while ((token = parser.parseNextToken()) != null) {
                        if (token instanceof Operator) {
                            // process operator
                            Operator operator = (Operator) token;
                            COSName name;
                            switch (operator.getName()) {
                                case OperatorName.DRAW_OBJECT:
                                    tokens.clear();
                                    continue;
                                case OperatorName.SET_FONT_AND_SIZE:
                                    name = (COSName) tokens.get(0);
                                    PDFont ft = overlay.getResources().getFont(name);
                                    tokens.set(0, page.getResources().add(ft));
                                    break;
                                case OperatorName.STROKING_COLORSPACE:
                                case OperatorName.NON_STROKING_COLORSPACE:
                                    name = (COSName) tokens.get(0);
                                    PDColorSpace cs = overlay.getResources().getColorSpace(name);
                                    tokens.set(0, page.getResources().add(cs));
                                    break;
                                case OperatorName.SET_GRAPHICS_STATE_PARAMS:
                                    name = (COSName) tokens.get(0);
                                    PDExtendedGraphicsState egs = overlay.getResources().getExtGState(name);
                                    tokens.set(0, page.getResources().add(egs));
                                    break;
                                case OperatorName.SHADING_FILL:
                                    name = (COSName) tokens.get(0);
                                    PDShading sh = overlay.getResources().getShading(name);
                                    tokens.set(0, page.getResources().add(sh));
                                    break;
                                case OperatorName.BEGIN_MARKED_CONTENT_SEQ:
                                    name = (COSName) tokens.get(0);
                                    PDPropertyList pl = overlay.getResources().getProperties(name);
                                    tokens.set(0, page.getResources().add(pl));
                                    break;
                            }
                            writer.writeTokens(tokens);
                            writer.writeToken(operator);
                            tokens.clear();
                        } else {
                            tokens.add(token);
                        }
                    }
                    writer.writeToken(Operator.getOperator(OperatorName.RESTORE));
                }
                pageDictionary.setItem(COSName.CONTENTS, stream);
                addTestToProcSet(page.getResources().getCOSObject());
            }

        }

    }

    static private void trashAndReplace(Path source, Path target) throws IOException {
        if (Files.isRegularFile(target)) {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                    desktop.moveToTrash(target.toFile());
                }
            }
            Files.deleteIfExists(target);
        }
        Files.move(source, target);
    }

    static private void addTestToProcSet(COSDictionary object) {
        COSArray procSet = object.getCOSArray(COSName.PROC_SET);
        if (procSet == null) {
            procSet = new COSArray();
            object.setItem(COSName.PROC_SET, procSet);
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < procSet.size(); i++) {
            names.add(procSet.getName(i));
        }
        int iPdf = names.indexOf("PDF");
        int iText = names.indexOf("Text");
        if (iPdf < 0) {
            names.add(0, "PDF");
            iPdf = 0;
        }
        if (iText < 0) {
            names.add(iPdf + 1, "Text");
        }
        procSet.clear();
        for (String name : names) {
            procSet.add(COSName.getPDFName(name));
        }
    }

    interface ImageProcessor {
        PDImageXObject process(String name, PDImageXObject xObject) throws IOException;
    }

    static private void processImages(String location, PDResources resources, ImageProcessor processor) throws IOException {
        if (resources != null) {
            for (COSName xObjectName : resources.getXObjectNames()) {
                String nloc = location + "/" + xObjectName.getName();
                PDXObject xObject = resources.getXObject(xObjectName);
                if (xObject instanceof PDFormXObject) {
                    processImages(nloc, ((PDFormXObject) xObject).getResources(), processor);
                } else if (xObject instanceof PDImageXObject) {
                    PDImageXObject obj = processor.process(nloc, (PDImageXObject) xObject);
                    resources.put(xObjectName, obj);
                }
            }
        }
    }


    private PDImageXObject compress(PDDocument document, PDImageXObject xObject, Matrix scale) throws IOException {
        BufferedImage bi = xObject.getImage();
        if (bi.getTransparency() != BufferedImage.OPAQUE) {
            bi = xObject.getOpaqueImage();
        }
        if (scale != null) {
            float sx = Math.abs(scale.getScalingFactorX());
            float sy = Math.abs(scale.getScalingFactorY());
            if (bi.getWidth() * 72 / sx > resolution * 1.25
            || bi.getHeight() * 72 / sy > resolution * 1.25) {

                int nx = Math.round(resolution * sx / 72);
                int ny = Math.round(resolution * sy / 72);
                Image img = bi.getScaledInstance(nx, ny, Image.SCALE_SMOOTH);
                bi = new BufferedImage(nx, ny, BufferedImage.TYPE_INT_RGB);
                bi.getGraphics().drawImage(img, 0, 0, null);
                return JPEGFactory.createFromImage(document, bi, quality, resolution);
            }
        }
        return xObject;
    }

    static class PrintImageLocations extends PDFStreamEngine {
        final Map<String, Matrix> scale = new HashMap<>();
        final Deque<String> locs = new ArrayDeque<>();

        public PrintImageLocations() {
            addOperator(new Concatenate());
            addOperator(new DrawObject());
            addOperator(new SetGraphicsStateParameters());
            addOperator(new Save());
            addOperator(new Restore());
            addOperator(new SetMatrix());
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            String operation = operator.getName();
            if (OperatorName.DRAW_OBJECT.equals(operation)) {
                COSName objectName = (COSName) operands.get(0);
                PDXObject xobject = getResources().getXObject(objectName);
                locs.push("/" + objectName.getName());
                if( xobject instanceof PDImageXObject) {
                    Matrix ctmNew = getGraphicsState().getCurrentTransformationMatrix();
                    scale.put(String.join("", locs), ctmNew.clone());
                } else if (xobject instanceof PDFormXObject) {
                    PDFormXObject form = (PDFormXObject)xobject;
                    showForm(form);
                }
                locs.pop();
            } else {
                super.processOperator(operator, operands);
            }
        }
    }

    private static class FilteringPDFRenderer extends PDFRenderer {
        final boolean text;
        final boolean images;
        final boolean paths;

        public FilteringPDFRenderer(PDDocument document, boolean text, boolean images, boolean paths) {
            super(document);
            this.text = text;
            this.images = images;
            this.paths = paths;
        }

        @Override
        protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            PageDrawer drawer = new PageDrawer(parameters) {
                protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code,
                                         String unicode, Vector displacement) throws IOException {
                    if (text) {
                        super.showGlyph(textRenderingMatrix, font, code, unicode, displacement);
                    }
                }
                @Override
                public void strokePath() throws IOException {
                    if (paths) {
                        super.strokePath();
                    }
                }
                @Override
                public void fillPath(int windingRule) throws IOException {
                    if (paths) {
                        super.fillPath(windingRule);
                    }
                }

                @Override
                public void drawImage(PDImage pdImage) throws IOException {
                    if (images) {
                        super.drawImage(pdImage);
                    }
                }
            };
            drawer.setAnnotationFilter(getAnnotationsFilter());
            return drawer;
        }
    }

}