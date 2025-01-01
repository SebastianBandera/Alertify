package app.alertify.controller.filter;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import app.alertify.service.GlobalStatus;

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
