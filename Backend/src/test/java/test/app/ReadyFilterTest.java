package test.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import app.alertify.controller.filter.ReadyFilter;
import app.alertify.service.GlobalStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class ReadyFilterTest {

	@Mock
	private GlobalStatus globalStatus;
	
    @InjectMocks
    private ReadyFilter readyFilter;

    @BeforeEach
    void setUp() {
    	MockitoAnnotations.openMocks(this);
    }
    
	@Test
	@DisplayName("No está listo en un inicio")
    void testNotReady() {
		assertFalse(globalStatus.isReady());
    }

	@Test
	@DisplayName("Puede pasar a listo")
    void testReady() {
		when(globalStatus.isReady()).thenReturn(true);
		
		assertTrue(globalStatus.isReady());
    }

	@Test
	@DisplayName("El filtro devuelve SC_SERVICE_UNAVAILABLE si no está listo")
    void testFilterNotReady() throws IOException, ServletException {
	    HttpServletRequest request = mock(HttpServletRequest.class);
	    HttpServletResponse response = mock(HttpServletResponse.class);
	    FilterChain chain = mock(FilterChain.class);
	    
	    readyFilter.doFilter(request, response, chain);
	    
	    verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
	    verify(chain, never()).doFilter(any(), any());
    }

	@Test
	@DisplayName("El filtro continúa chain.doFilter si está listo")
    void testFilterReady() throws IOException, ServletException {
		when(globalStatus.isReady()).thenReturn(true);
		
	    HttpServletRequest request = mock(HttpServletRequest.class);
	    HttpServletResponse response = mock(HttpServletResponse.class);
	    FilterChain chain = mock(FilterChain.class);
	    
	    readyFilter.doFilter(request, response, chain);

	    verify(chain).doFilter(request, response);
	    verify(response, never()).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }
	
}
