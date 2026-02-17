package app.alertify.controller.filter;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import app.alertify.service.GlobalStatus;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(1)
public class ReadyFilter implements Filter {
	
	private static final Logger log = LoggerFactory.getLogger(ReadyFilter.class);

	@Autowired
	private GlobalStatus status;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if(!status.isReady()) {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
        	HttpServletResponse httpResponse = (HttpServletResponse) response;
        	httpResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            log.warn("SERVICE_UNAVAILABLE, not ready yet. Request: " + httpRequest.getRequestURI());
        	return;
        }
    	
        chain.doFilter(request, response);
    }

}
