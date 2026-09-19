package iwkms.roomflow.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import iwkms.roomflow.config.storage.S3Properties;
import iwkms.roomflow.exception.InvalidFileException;
import iwkms.roomflow.modules.booking.impl.service.FileStorageService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {
    @Mock
    private S3Client client;

    @Mock
    private S3Presigner presigner;

    private FileStorageService service;
    private S3Properties properties;
    private static final byte[] PDF = "%PDF-1.4\nDocument".getBytes(StandardCharsets.US_ASCII);

    @BeforeEach
    void setUp() {
        properties = new S3Properties();
        properties.setBucket("test-files");
        service = new FileStorageService(client, presigner, properties);
    }

    @Test
    void uploadsValidPdfWithRandomStorageKeyAndNormalizedType() throws IOException {
        String key = service.uploadFile(new MockMultipartFile("file", "../../secret.pdf", " APPLICATION/PDF ", PDF));
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertTrue(key.matches("rooms/[0-9a-f-]{36}\\.pdf"));
        assertEquals(key, request.getValue().key());
        assertEquals("test-files", request.getValue().bucket());
        assertEquals("application/pdf", request.getValue().contentType());
        try (var stream = body.getValue().contentStreamProvider().newStream()) {
            assertArrayEquals(PDF, stream.readAllBytes());
        }
    }

    @Test
    void pngAndJpegSignaturesAreAcceptedWithCorrectExtensions() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        assertTrue(service.uploadFile(new MockMultipartFile("file", "image", "image/png", png))
                .endsWith(".png"));
        assertTrue(service.uploadFile(new MockMultipartFile("file", "image", "image/jpeg", jpeg))
                .endsWith(".jpg"));
        verify(client, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void missingEmptyAndUnsupportedFilesNeverReachObjectStorage() {
        assertThrows(InvalidFileException.class, () -> service.uploadFile(null));
        assertThrows(InvalidFileException.class, () -> service.uploadFile(new MockMultipartFile("file", new byte[0])));
        assertThrows(
                InvalidFileException.class, () -> service.uploadFile(new MockMultipartFile("file", "file", null, PDF)));
        assertThrows(
                InvalidFileException.class,
                () -> service.uploadFile(new MockMultipartFile("file", "file", "text/html", PDF)));
        verifyNoInteractions(client);
    }

    @Test
    void spoofedAndTruncatedSignaturesAreRejected() {
        for (String mime : new String[] {"image/png", "image/jpeg", "application/pdf"}) {
            assertThrows(
                    InvalidFileException.class,
                    () -> service.uploadFile(new MockMultipartFile("file", "fake", mime, new byte[] {1})));
            assertThrows(
                    InvalidFileException.class,
                    () -> service.uploadFile(new MockMultipartFile(
                            "file", "fake", mime, "not a file".getBytes(StandardCharsets.US_ASCII))));
        }
        verifyNoInteractions(client);
    }

    @Test
    void fiveMegabytesAreAcceptedButOneByteOverIsRejected() {
        byte[] maximum = new byte[5 * 1024 * 1024];
        System.arraycopy(PDF, 0, maximum, 0, PDF.length);
        assertTrue(service.uploadFile(new MockMultipartFile("file", "full.pdf", "application/pdf", maximum))
                .endsWith(".pdf"));
        assertThrows(
                InvalidFileException.class,
                () -> service.uploadFile(
                        new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[maximum.length + 1])));
        verify(client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void fileReadFailureBecomesValidationErrorWithoutAnUpload() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getBytes()).thenThrow(new IOException("read failed"));
        InvalidFileException error = assertThrows(InvalidFileException.class, () -> service.uploadFile(file));
        assertInstanceOf(IOException.class, error.getCause());
        verifyNoInteractions(client);
    }

    @Test
    void signedDownloadUsesConfiguredBucketKeyAndExpiry() throws Exception {
        var signed = mock(PresignedGetObjectRequest.class);
        when(signed.url())
                .thenReturn(
                        URI.create("https://storage.example.com/signed-object").toURL());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(signed);
        properties.setPresignTtlMinutes(7);
        assertEquals("https://storage.example.com/signed-object", service.generatePresignedUrl("rooms/test.pdf"));
        var request = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(request.capture());
        assertEquals(Duration.ofMinutes(7), request.getValue().signatureDuration());
        assertEquals("test-files", request.getValue().getObjectRequest().bucket());
        assertEquals("rooms/test.pdf", request.getValue().getObjectRequest().key());
    }

    @Test
    void deletingAnAttachmentTargetsOnlyItsStorageObject() {
        service.deleteFile("rooms/test.pdf");
        var request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(request.capture());
        assertEquals("test-files", request.getValue().bucket());
        assertEquals("rooms/test.pdf", request.getValue().key());
    }
}
