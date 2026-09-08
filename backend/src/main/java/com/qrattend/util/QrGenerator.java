package com.qrattend.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Utility class for generating QR code images using the ZXing library.
 *
 * <p>All methods are static. No Spring injection is required.</p>
 */
public final class QrGenerator {

    /** Prevents instantiation of this utility class. */
    private QrGenerator() {}

    /**
     * Encodes {@code content} as a QR code and returns the result as a PNG byte array.
     *
     * <p>Error correction level M (15% redundancy) is used — a good balance between
     * data density and robustness when projected on a classroom screen.</p>
     *
     * @param content the text or URL to encode (e.g., a scan URL with a JWT)
     * @param width   the width of the output PNG in pixels
     * @param height  the height of the output PNG in pixels
     * @return a byte array containing the raw PNG image data
     * @throws WriterException if ZXing cannot encode the content (e.g., content too long)
     * @throws IOException     if writing to the internal byte stream fails
     */
    public static byte[] generatePng(String content, int width, int height)
            throws WriterException, IOException {

        Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                // Suppress the 4-module quiet zone — keeps the QR compact but still scannable
                EncodeHintType.MARGIN, 1
        );

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints);

        // MatrixToImageConfig defaults: black on white — correct for projection
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream, new MatrixToImageConfig());

        return outputStream.toByteArray();
    }
}
