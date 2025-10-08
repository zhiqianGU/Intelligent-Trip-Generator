package thesis.project.gu.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /* ---------- 0. 业务异常：按 ErrorCode 的 HttpStatus 返回 ---------- */
    @ExceptionHandler(NavigatorException.class)
    public ResponseEntity<ErrorResponse> handleBiz(NavigatorException ex, HttpServletRequest req) {
        ErrorCode ec = ex.getErrorCode();
        HttpStatus status = (ec != null ? ec.getStatus() : HttpStatus.UNPROCESSABLE_ENTITY);
        return ResponseEntity
                .status(status)
                .body(ErrorResponse.of(ex.getCode(), ex.getMessage(), req.getRequestURI()));
    }

    /* ---------- 1. 参数校验/绑定相关 ---------- */
    // @RequestParam/@PathVariable 等方法参数的校验失败
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest req) {
        String msg = ex.getConstraintViolations().stream()
                .findFirst().map(v -> v.getPropertyPath() + " " + v.getMessage())
                .orElse("参数校验失败");
        return badRequest("VALIDATION_ERROR", msg, req);
    }

    // 绑定失败（类型不匹配、格式错误等也可能到这里）
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBind(BindException ex, HttpServletRequest req) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst().map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("参数绑定失败");
        return badRequest("VALIDATION_ERROR", msg, req);
    }

    // @RequestBody 校验失败（如果你有 DTO）
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgNotValid(MethodArgumentNotValidException ex,
                                                                 HttpServletRequest req) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst().map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("参数校验失败");
        return badRequest("VALIDATION_ERROR", msg, req);
    }

    // 缺少必须参数
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                            HttpServletRequest req) {
        String msg = "缺少必填参数: " + ex.getParameterName();
        return badRequest("VALIDATION_ERROR", msg, req);
    }

    // 类型转换失败（如 ?limit=abc 需要 int）
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest req) {
        String msg = "参数类型错误: " + ex.getName();
        return badRequest("VALIDATION_ERROR", msg, req);
    }

    // 你代码里手工抛出的非法参数
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArg(IllegalArgumentException ex,
                                                          HttpServletRequest req) {
        return badRequest("VALIDATION_ERROR", ex.getMessage(), req);
    }

    /* ---------- 2. 兜底 ---------- */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOthers(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "服务器开小差了", req.getRequestURI()));
    }

    /* ---------- 工具方法 & 响应体 ---------- */
    private ResponseEntity<ErrorResponse> badRequest(String code, String msg, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(code, msg, req.getRequestURI()));
    }

    public record ErrorResponse(String code, String message, String path, String timestamp) {
        static ErrorResponse of(String code, String message, String path) {
            return new ErrorResponse(code, message, path, OffsetDateTime.now().toString());
        }
    }
}
