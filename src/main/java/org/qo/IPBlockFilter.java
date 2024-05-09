package org.qo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.HttpStatus;

import java.io.IOException;

public class IPBlockFilter extends OncePerRequestFilter {

    private final IPBlockService ipBlockService = new IPBlockService("blockIP.txt");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String clientIp = request.getRemoteAddr();
        if (ipBlockService.isBlocked(clientIp)) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "Access is denied for your IP address");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
