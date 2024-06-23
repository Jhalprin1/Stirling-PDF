package stirling.software.SPDF.controller.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.github.pixee.security.Filenames;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import stirling.software.SPDF.model.api.PDFFile;
import stirling.software.SPDF.utils.WebResponseUtils;

@RestController
@RequestMapping("/api/v1/general")
@Tag(name = "General", description = "General APIs")
public class SplitPdfVerticallyController {

    private static final Logger logger = LoggerFactory.getLogger(SplitPdfVerticallyController.class);

    @PostMapping(value = "/split-pdf-vertically", consumes = "multipart/form-data")
    @Operation(
            summary = "Split PDF pages vertically into two sections",
            description = "Split each page of a PDF vertically into two sections. Input: PDF Output: ZIP-PDF Type: SISO")
    public ResponseEntity<byte[]> splitPdf(@ModelAttribute PDFFile request) throws Exception {
        List<ByteArrayOutputStream> splitDocumentsBoas = new ArrayList<>();

        MultipartFile file = request.getFileInput();
        PDDocument sourceDocument = Loader.loadPDF(file.getBytes());

        List<PDDocument> splitDocuments = splitPdfPages(sourceDocument);

        String filename = Filenames.toSimpleFileName(file.getOriginalFilename()).replaceFirst("[.][^.]+$", "");
        for (PDDocument doc : splitDocuments) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            doc.close();
            splitDocumentsBoas.add(baos);
        }

        sourceDocument.close();

        Path zipFile = Files.createTempFile("split_documents", ".zip");
        byte[] data;

        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            int pageNum = 1;
            for (int i = 0; i < splitDocumentsBoas.size(); i++) {
                ByteArrayOutputStream baos = splitDocumentsBoas.get(i);
                String fileName = filename + "_" + pageNum + "_1.pdf";
                byte[] pdf = baos.toByteArray();
                ZipEntry pdfEntry = new ZipEntry(fileName);
                zipOut.putNextEntry(pdfEntry);
                zipOut.write(pdf);
                zipOut.closeEntry();

                pageNum++;
            }
        } catch (Exception e) {
            logger.error("exception", e);
        } finally {
            data = Files.readAllBytes(zipFile);
            Files.deleteIfExists(zipFile);
        }

        return WebResponseUtils.bytesToWebResponse(data, filename + "_split.zip", MediaType.APPLICATION_OCTET_STREAM);
    }

    public List<PDDocument> splitPdfPages(PDDocument document) throws IOException {
        List<PDDocument> splitDocuments = new ArrayList<>();

        for (PDPage originalPage : document.getPages()) {
            PDRectangle originalMediaBox = originalPage.getMediaBox();
            float width = originalMediaBox.getWidth();
            float height = originalMediaBox.getHeight();
            float subPageWidth = width / 2;

            LayerUtility layerUtility = new LayerUtility(document);

            for (int i = 0; i < 2; i++) {
                PDDocument subDoc = new PDDocument();
                PDPage subPage = new PDPage(new PDRectangle(subPageWidth, height));
                subDoc.addPage(subPage);

                PDFormXObject form = layerUtility.importPageAsForm(document, document.getPages().indexOf(originalPage));

                try (PDPageContentStream contentStream = new PDPageContentStream(subDoc, subPage, AppendMode.APPEND, true, true)) {
                    float translateX = -subPageWidth * i;

                    contentStream.saveGraphicsState();
                    contentStream.addRect(0, 0, subPageWidth, height);
                    contentStream.clip();
                    contentStream.transform(new Matrix(1, 0, 0, 1, translateX, 0));

                    contentStream.drawForm(form);
                    contentStream.restoreGraphicsState();
                }

                splitDocuments.add(subDoc);
            }
        }

        return splitDocuments;
    }
}
