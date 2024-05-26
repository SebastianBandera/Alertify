package app.watchful.controller.filter;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import app.watchful.service.GlobalStatus;
import lombok.extern.slf4j.Slf4j;

@Component
@Order(1)
@Slf4j
public class ReadyFilter implements Filter {

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
