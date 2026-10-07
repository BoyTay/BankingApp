package vn.edu.wallet.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiDtos.ErrorView> known(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(new ApiDtos.ErrorView(ex.code(), ex.getMessage(), UUID.randomUUID()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiDtos.ErrorView> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(new ApiDtos.ErrorView("INVALID_REQUEST", "Dữ liệu yêu cầu không hợp lệ", UUID.randomUUID()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiDtos.ErrorView> unexpected(Exception ex) {
        UUID traceId = UUID.randomUUID();
        LOG.error("Unhandled API error traceId={}", traceId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiDtos.ErrorView("INTERNAL_ERROR", "Lỗi máy chủ", traceId));
    }
}
