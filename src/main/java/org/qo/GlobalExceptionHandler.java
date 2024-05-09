package org.qo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import static org.qo.Logger.LogLevel.*;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleInvalidRequest(Exception e, HttpServletRequest request) {
        Logger.log("error from ip " + request.getRemoteAddr(), ERROR);
        return new ResponseEntity<>("Invalid request", HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleGeneralException(Exception e, HttpServletRequest request) {
        Logger.log("error from ip " + request.getRemoteAddr(), ERROR);
        return new ResponseEntity<>("Invalid request", HttpStatus.BAD_REQUEST);
    }
    @ExceptionHandler(InvalidMediaTypeException.class)
    public ResponseEntity<String> handleException(Exception e, HttpServletRequest request) {
        Logger.log("error from ip " + request.getRemoteAddr(), ERROR);
        return new ResponseEntity<>("Invalid request", HttpStatus.BAD_REQUEST);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGlobalException(Exception e, HttpServletRequest request) {
        Logger.log("error from ip " + request.getRemoteAddr(), ERROR);
        return new ResponseEntity<>("done.", HttpStatus.OK);
    }
}
