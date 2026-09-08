package com.qrattend.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;


import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link QrGenerator}.
 *
 * <p>No Spring context is loaded. ZXing's own reader is used to verify that
 * the generated PNG is a correctly encoded and decodable QR code.</p>
 */
@DisplayName("QrGenerator")
class QrGeneratorTest {

    private static final String SAMPLE_URL =
            "http://localhost:5173/scan?token=eyJhbGciOiJIUzI1NiJ9.sample.token";

    // ══════════════════════════════════════════════════════════════
    //  Output format — PNG header / image validity
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PNG format")
    class PngFormat {

        @Test
        @DisplayName("generatePng returns a non-empty byte array")
        void returnsNonEmptyByteArray() throws WriterException, IOException {
            byte[] png = QrGenerator.generatePng(SAMPLE_URL, 300, 300);
            assertThat(png).isNotEmpty();
        }

        @Test
        @DisplayName("generatePng returns bytes with the PNG magic number (\\x89PNG)")
        void startWithPngMagicNumber() throws WriterException, IOException {
            byte[] png = QrGenerator.generatePng(SAMPLE_URL, 300, 300);

            // PNG magic: 0x89 0x50 0x4E 0x47 (i.e. \x89PNG)
            assertThat(png[0] & 0xFF).isEqualTo(0x89);
            assertThat(png[1] & 0xFF).isEqualTo(0x50); // 'P'
            assertThat(png[2] & 0xFF).isEqualTo(0x4E); // 'N'
            assertThat(png[3] & 0xFF).isEqualTo(0x47); // 'G'
        }

        @Test
        @DisplayName("generatePng output can be read as a valid BufferedImage")
        void outputIsValidImage() throws WriterException, IOException {
            byte[] png = QrGenerator.generatePng(SAMPLE_URL, 300, 300);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            assertThat(image).isNotNull();
        }

        @Test
        @DisplayName("generatePng produces an image with the requested width and height")
        void imageDimensionsMatchRequest() throws WriterException, IOException {
            byte[] png = QrGenerator.generatePng(SAMPLE_URL, 300, 300);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));

            assertThat(image.getWidth()).isEqualTo(300);
            assertThat(image.getHeight()).isEqualTo(300);
        }

        @Test
        @DisplayName("generatePng respects non-square dimensions")
        void nonSquareDimensions() throws WriterException, IOException {
            byte[] png = QrGenerator.generatePng(SAMPLE_URL, 400, 200);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));

            assertThat(image.getWidth()).isEqualTo(400);
            assertThat(image.getHeight()).isEqualTo(200);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Decodability — the generated QR must scan correctly
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("QR decodability")
    class QrDecodability {

        /**
         * Decodes a PNG byte array using ZXing's MultiFormatReader.
         */
        private String decode(byte[] png) throws IOException, NotFoundException {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            BinaryBitmap bitmap = new BinaryBitmap(
                    new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            // POSSIBLE_FORMATS requires a Collection<BarcodeFormat>, not a bare BarcodeFormat
            Result result = new MultiFormatReader().decode(
                    bitmap, Map.of(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE)));
            return result.getText();
        }

        @Test
        @DisplayName("A generated QR code decodes back to the original URL")
        void decodesBackToOriginalContent() throws WriterException, IOException, NotFoundException {
            byte[] png = QrGenerator.generatePng(SAMPLE_URL, 300, 300);
            String decoded = decode(png);
            assertThat(decoded).isEqualTo(SAMPLE_URL);
        }

        @Test
        @DisplayName("A short plain-text content round-trips correctly")
        void shortTextRoundTrip() throws WriterException, IOException, NotFoundException {
            String content = "hello-qr";
            byte[] png = QrGenerator.generatePng(content, 200, 200);
            assertThat(decode(png)).isEqualTo(content);
        }

        @Test
        @DisplayName("A long JWT scan URL (≈300 chars) is decodable at 300×300 px")
        void longJwtUrlDecodableAt300px() throws WriterException, IOException, NotFoundException {
            // Realistic scan URL with a full-length JWT
            String longUrl = "http://localhost:5173/scan?token="
                    + "eyJhbGciOiJIUzI1NiJ9"
                    + ".eyJzdWIiOiIwMTIzNDU2Ny04OWFiLWNkZWYtMDEyMy00NTY3ODlhYmNkZWYiLCJ0eXBlIjoiU0NBTiIsImlhdCI6MTczMDAwMDAwMCwiZXhwIjoxNzMwMDAwMDE1fQ"
                    + ".XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";

            byte[] png = QrGenerator.generatePng(longUrl, 300, 300);
            assertThat(decode(png)).isEqualTo(longUrl);
        }

        @Test
        @DisplayName("Content with special characters (?, =, &) round-trips correctly")
        void urlWithQueryParamsRoundTrips() throws WriterException, IOException, NotFoundException {
            String url = "http://localhost:5173/scan?token=abc123&session=xyz&foo=bar%20baz";
            byte[] png = QrGenerator.generatePng(url, 300, 300);
            assertThat(decode(png)).isEqualTo(url);
        }

        @Test
        @DisplayName("Different contents produce different PNG outputs")
        void differentContentsProduceDifferentOutputs() throws WriterException, IOException {
            byte[] png1 = QrGenerator.generatePng("content-alpha", 300, 300);
            byte[] png2 = QrGenerator.generatePng("content-beta", 300, 300);
            assertThat(png1).isNotEqualTo(png2);
        }

        @Test
        @DisplayName("Same content always produces the same PNG (deterministic)")
        void sameContentProducesSameOutput() throws WriterException, IOException {
            byte[] png1 = QrGenerator.generatePng(SAMPLE_URL, 300, 300);
            byte[] png2 = QrGenerator.generatePng(SAMPLE_URL, 300, 300);
            assertThat(png1).isEqualTo(png2);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Edge cases & error conditions
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Very small image (50×50) still produces valid PNG bytes")
        void smallImageProducesValidPng() throws WriterException, IOException {
            byte[] png = QrGenerator.generatePng(SAMPLE_URL, 50, 50);
            assertThat(png).isNotEmpty();
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            assertThat(image).isNotNull();
        }

        @Test
        @DisplayName("Large image (1000×1000) produces valid PNG bytes")
        void largeImageProducesValidPng() throws WriterException, IOException {
            byte[] png = QrGenerator.generatePng(SAMPLE_URL, 1000, 1000);
            assertThat(png).isNotEmpty();
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            assertThat(image.getWidth()).isEqualTo(1000);
        }

        @Test
        @DisplayName("Null content throws WriterException or IllegalArgumentException")
        void nullContentThrowsException() {
            assertThatThrownBy(() -> QrGenerator.generatePng(null, 300, 300))
                    .isInstanceOf(Exception.class); // WriterException or IllegalArgumentException
        }

        @Test
        @DisplayName("Empty string content throws IllegalArgumentException (ZXing rejects empty content)")
        void emptyStringContent() {
            // ZXing QRCodeWriter.encode() throws IllegalArgumentException for empty string
            assertThatThrownBy(() -> QrGenerator.generatePng("", 300, 300))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Utility class design
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Utility class design")
    class UtilityClassDesign {

        @Test
        @DisplayName("QrGenerator cannot be instantiated (has no public constructor)")
        void cannotBeInstantiated() {
            var constructors = QrGenerator.class.getDeclaredConstructors();
            assertThat(constructors).hasSize(1);
            assertThat(constructors[0].canAccess(null)).isFalse();
        }

        @Test
        @DisplayName("generatePng is a static method")
        void generatePngIsStatic() throws NoSuchMethodException {
            var method = QrGenerator.class.getDeclaredMethod("generatePng", String.class, int.class, int.class);
            assertThat(java.lang.reflect.Modifier.isStatic(method.getModifiers())).isTrue();
        }
    }
}
