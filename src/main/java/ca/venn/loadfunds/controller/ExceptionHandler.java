package ca.venn.loadfunds.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class ExceptionHandler {

    @org.springframework.web.bind.annotation.ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException exception,
                                                   HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        exception.getBindingResult()
                 .getFieldErrors()
                 .forEach(error ->
                              errors.putIfAbsent(
                                  error.getField(),
                                  error.getDefaultMessage()
                              )
                 );

        log.atDebug()
           .addKeyValue("path", request.getRequestURI())
           .addKeyValue("fields", errors.keySet())
           .log("Request validation failed");

        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            "Invalid fields: " + String.join(", ", errors.keySet()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleUnreadableRequest(HttpMessageNotReadableException exception,
                                                 HttpServletRequest request) {
        log.atDebug()
           .addKeyValue("path", request.getRequestURI())
           .addKeyValue("exceptionType", exception.getClass().getSimpleName())
           .log("Malformed request body");

        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed JSON request");
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        log.atError()
           .addKeyValue("path", request.getRequestURI())
           .addKeyValue("exceptionType", exception.getClass().getName())
           .addKeyValue("message", exception.getMessage())
           .setCause(exception)
           .log("Unhandled request error");
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
