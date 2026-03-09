package test.app.alertify.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import app.alertify.control.ControlResponse;
import app.alertify.control.generic.WebRequest;
import app.alertify.control.generic.WebRequest.Params;

@ExtendWith(MockitoExtension.class)
public class WebRequestTest {

    public static Stream<HttpMethod> httpMethods() {
        return Stream.of(
            HttpMethod.GET,
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.DELETE,
            HttpMethod.PATCH,
            HttpMethod.OPTIONS,
            HttpMethod.HEAD,
            HttpMethod.TRACE
        );
    }
    
	@Mock
	private RestTemplate restTemplate;
	
    private WebRequest webRequest;
    
    @BeforeEach
    void setup() {
    	Supplier<RestTemplate> restSupplier = () -> restTemplate;
    	webRequest = new WebRequest(restSupplier);
    }
    
    @ParameterizedTest(name = "HTTP method = {0}")
    @MethodSource("httpMethods")
    @DisplayName("ok1 - all HTTP methods")
    void ok_all_methods(HttpMethod methodObject) {

        String url = "url";
        String method = methodObject.toString();
        Integer responseExpected = HttpStatus.OK.value();

        Map<String, Object> params = new HashMap<>();
        params.put(Params.URL.toString(), url);
        params.put(Params.METHOD.toString(), method);
        params.put(Params.BODY.toString(), null);
        params.put(Params.HEADERS.toString(), new Object[]{});
        params.put(Params.RESPONSE_CODE_EXPECTED.toString(), responseExpected);
        params.put(Params.REGEX_EXTRA_CHECK.toString(), new Object[]{});

        ResponseEntity<String> responseEntity =
                new ResponseEntity<>("ok", HttpStatus.OK);

        Mockito.when(restTemplate.exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(responseEntity);

        ControlResponse response = webRequest.execute(params);

        Mockito.verify(restTemplate).exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        );

        assertTrue(response.getStatus().isSuccess());
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.STATUS_CODE.toString()));
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.RESPONSE_CODE_EXPECTED.toString()));
    }
    
    @Test
	@DisplayName("ok with headers")
    void okWithheaders() {
    	HttpMethod methodObject = HttpMethod.GET;
    	
        String url = "url";
        String method = methodObject.toString();
        Integer responseExpected = HttpStatus.OK.value();
        Object[] headers = new Object[] {"header1:value1", "header2:value2"};
        Object[] regexCheck = new Object[] {};

        Map<String, Object> params = new HashMap<>();
        params.put(Params.URL.toString(), url);
        params.put(Params.METHOD.toString(), method);
        params.put(Params.BODY.toString(), null);
        params.put(Params.HEADERS.toString(), headers);
        params.put(Params.RESPONSE_CODE_EXPECTED.toString(), responseExpected);
        params.put(Params.REGEX_EXTRA_CHECK.toString(), regexCheck);

        ResponseEntity<String> responseEntity =
                new ResponseEntity<>("ok", HttpStatus.OK);

        Mockito.when(restTemplate.exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(responseEntity);

        ControlResponse response = webRequest.execute(params);

        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        
        Mockito.verify(restTemplate).exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                entityCaptor.capture(),
                Mockito.eq(String.class)
        );

        assertTrue(response.getStatus().isSuccess());
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.STATUS_CODE.toString()));
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.RESPONSE_CODE_EXPECTED.toString()));
        
        HttpEntity<?> entity = entityCaptor.getValue();
        HttpHeaders httpHeaders = entity.getHeaders();

        assertEquals("value1", httpHeaders.getFirst("header1"));
        assertEquals("value2", httpHeaders.getFirst("header2"));
    }
    
    @Test
	@DisplayName("fail with headers")
    void failWithheaders() {
    	HttpMethod methodObject = HttpMethod.GET;
    	
        String url = "url";
        String method = methodObject.toString();
        Integer responseExpected = HttpStatus.OK.value();
        Object[] headers = new Object[] {"header1 no separator value"};
        Object[] regexCheck = new Object[] {};

        Map<String, Object> params = new HashMap<>();
        params.put(Params.URL.toString(), url);
        params.put(Params.METHOD.toString(), method);
        params.put(Params.BODY.toString(), null);
        params.put(Params.HEADERS.toString(), headers);
        params.put(Params.RESPONSE_CODE_EXPECTED.toString(), responseExpected);
        params.put(Params.REGEX_EXTRA_CHECK.toString(), regexCheck);

        ResponseEntity<String> responseEntity =
                new ResponseEntity<>("ok", HttpStatus.OK);

        Mockito.when(restTemplate.exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(responseEntity);

        ControlResponse response = webRequest.execute(params);

        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        
        Mockito.verify(restTemplate).exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                entityCaptor.capture(),
                Mockito.eq(String.class)
        );

        assertTrue(response.getStatus().isSuccess());
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.STATUS_CODE.toString()));
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.RESPONSE_CODE_EXPECTED.toString()));
        
        HttpEntity<?> entity = entityCaptor.getValue();
        HttpHeaders httpHeaders = entity.getHeaders();

        assertEquals(0, httpHeaders.size()); //ignora headers mal configurados
    }
    
    @Test
	@DisplayName("ok with fallback config 1")
    void okWithfallbackconfig1() {
        String url = "url";
        String method = null;
        Integer responseExpected = HttpStatus.OK.value();
        Object[] headers = new Object[] {};
        Object[] regexCheck = new Object[] {};

        Map<String, Object> params = new HashMap<>();
        params.put(Params.URL.toString(), url);
        params.put(Params.METHOD.toString(), method);
        params.put(Params.BODY.toString(), null);
        params.put(Params.HEADERS.toString(), headers);
        params.put(Params.RESPONSE_CODE_EXPECTED.toString(), responseExpected);
        params.put(Params.REGEX_EXTRA_CHECK.toString(), regexCheck);

        ResponseEntity<String> responseEntity =
                new ResponseEntity<>("ok", HttpStatus.OK);

        Mockito.when(restTemplate.exchange(
                Mockito.eq(url),
                Mockito.eq(HttpMethod.GET),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(responseEntity);

        ControlResponse response = webRequest.execute(params);

        Mockito.verify(restTemplate).exchange(
                Mockito.eq(url),
                Mockito.eq(HttpMethod.GET),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        );

        assertTrue(response.getStatus().isSuccess());
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.STATUS_CODE.toString()));
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.RESPONSE_CODE_EXPECTED.toString()));
    }
    
    @Test
	@DisplayName("ok with fallback config 2")
    void okWithfallbackconfig2() {
        String url = "url";
        String method = "DEFAULT";
        Integer responseExpected = HttpStatus.OK.value();
        Object[] headers = new Object[] {};
        Object[] regexCheck = new Object[] {};

        Map<String, Object> params = new HashMap<>();
        params.put(Params.URL.toString(), url);
        params.put(Params.METHOD.toString(), method);
        params.put(Params.BODY.toString(), null);
        params.put(Params.HEADERS.toString(), headers);
        params.put(Params.RESPONSE_CODE_EXPECTED.toString(), responseExpected);
        params.put(Params.REGEX_EXTRA_CHECK.toString(), regexCheck);

        ResponseEntity<String> responseEntity =
                new ResponseEntity<>("ok", HttpStatus.OK);

        Mockito.when(restTemplate.exchange(
                Mockito.eq(url),
                Mockito.eq(HttpMethod.GET),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(responseEntity);

        ControlResponse response = webRequest.execute(params);

        Mockito.verify(restTemplate).exchange(
                Mockito.eq(url),
                Mockito.eq(HttpMethod.GET),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        );

        assertTrue(response.getStatus().isSuccess());
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.STATUS_CODE.toString()));
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.RESPONSE_CODE_EXPECTED.toString()));
    }
    
    @Test
	@DisplayName("ok with regex1")
    void okWithRegEx1() {
    	HttpMethod methodObject = HttpMethod.GET;
    	String returnBody = "ok";
    	
        String url = "url";
        String method = methodObject.toString();
        Integer responseExpected = HttpStatus.OK.value();
        Object[] headers = new Object[] {};
        Object[] regexCheck = new Object[]{returnBody}; // coincide con "ok"

        Map<String, Object> params = new HashMap<>();
        params.put(Params.URL.toString(), url);
        params.put(Params.METHOD.toString(), method);
        params.put(Params.BODY.toString(), null);
        params.put(Params.HEADERS.toString(), headers);
        params.put(Params.RESPONSE_CODE_EXPECTED.toString(), responseExpected);
        params.put(Params.REGEX_EXTRA_CHECK.toString(), regexCheck);

        ResponseEntity<String> responseEntity =
                new ResponseEntity<>(returnBody, HttpStatus.OK);

        Mockito.when(restTemplate.exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(responseEntity);

        ControlResponse response = webRequest.execute(params);
        
        Mockito.verify(restTemplate).exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        );

        assertTrue(response.getStatus().isSuccess());
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.STATUS_CODE.toString()));
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.RESPONSE_CODE_EXPECTED.toString()));
        assertEquals(true, response.getData().get(WebRequest.OutputParams.REGEX_RESULT_IS_VALID.toString()));
    }
    
    @Test
	@DisplayName("ok with regex2")
    void okWithRegEx2() {
    	HttpMethod methodObject = HttpMethod.GET;
    	String returnBody = "<html></html>";
    	
        String url = "url";
        String method = methodObject.toString();
        Integer responseExpected = HttpStatus.OK.value();
        Object[] headers = new Object[] {};
        Object[] regexCheck = new Object[]{"<html>(.*)</html>"};

        Map<String, Object> params = new HashMap<>();
        params.put(Params.URL.toString(), url);
        params.put(Params.METHOD.toString(), method);
        params.put(Params.BODY.toString(), null);
        params.put(Params.HEADERS.toString(), headers);
        params.put(Params.RESPONSE_CODE_EXPECTED.toString(), responseExpected);
        params.put(Params.REGEX_EXTRA_CHECK.toString(), regexCheck);

        ResponseEntity<String> responseEntity =
                new ResponseEntity<>(returnBody, HttpStatus.OK);

        Mockito.when(restTemplate.exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(responseEntity);

        ControlResponse response = webRequest.execute(params);
        
        Mockito.verify(restTemplate).exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        );

        assertTrue(response.getStatus().isSuccess());
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.STATUS_CODE.toString()));
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.RESPONSE_CODE_EXPECTED.toString()));
        assertEquals(true, response.getData().get(WebRequest.OutputParams.REGEX_RESULT_IS_VALID.toString()));
    }
    
    @Test
	@DisplayName("ok with regex3")
    void okWithRegEx3() {
    	HttpMethod methodObject = HttpMethod.GET;
    	String returnBody = "<html><head></head><body></body></html>";
    	
        String url = "url";
        String method = methodObject.toString();
        Integer responseExpected = HttpStatus.OK.value();
        Object[] headers = new Object[] {};
        Object[] regexCheck = new Object[]{"<html>(.*)</html>", "<head>(.*)</head>", "<body>(.*)</body>"};

        Map<String, Object> params = new HashMap<>();
        params.put(Params.URL.toString(), url);
        params.put(Params.METHOD.toString(), method);
        params.put(Params.BODY.toString(), null);
        params.put(Params.HEADERS.toString(), headers);
        params.put(Params.RESPONSE_CODE_EXPECTED.toString(), responseExpected);
        params.put(Params.REGEX_EXTRA_CHECK.toString(), regexCheck);

        ResponseEntity<String> responseEntity =
                new ResponseEntity<>(returnBody, HttpStatus.OK);

        Mockito.when(restTemplate.exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(responseEntity);

        ControlResponse response = webRequest.execute(params);
        
        Mockito.verify(restTemplate).exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        );

        assertTrue(response.getStatus().isSuccess());
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.STATUS_CODE.toString()));
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.RESPONSE_CODE_EXPECTED.toString()));
        assertEquals(true, response.getData().get(WebRequest.OutputParams.REGEX_RESULT_IS_VALID.toString()));
    }
    
    @Test
	@DisplayName("fail with regex1")
    void failWithRegEx1() {
    	HttpMethod methodObject = HttpMethod.GET;
    	String returnBody = "<html><head></head><body></error></html>";
    	
        String url = "url";
        String method = methodObject.toString();
        Integer responseExpected = HttpStatus.OK.value();
        Object[] headers = new Object[] {};
        Object[] regexCheck = new Object[]{"<html>(.*)</html>", "<head>(.*)</head>", "<body>(.*)</body>"};

        Map<String, Object> params = new HashMap<>();
        params.put(Params.URL.toString(), url);
        params.put(Params.METHOD.toString(), method);
        params.put(Params.BODY.toString(), null);
        params.put(Params.HEADERS.toString(), headers);
        params.put(Params.RESPONSE_CODE_EXPECTED.toString(), responseExpected);
        params.put(Params.REGEX_EXTRA_CHECK.toString(), regexCheck);

        ResponseEntity<String> responseEntity =
                new ResponseEntity<>(returnBody, HttpStatus.OK);

        Mockito.when(restTemplate.exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(responseEntity);

        ControlResponse response = webRequest.execute(params);
        
        Mockito.verify(restTemplate).exchange(
                Mockito.eq(url),
                Mockito.eq(methodObject),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        );

        assertTrue(response.getStatus().isWarn());
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.STATUS_CODE.toString()));
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.RESPONSE_CODE_EXPECTED.toString()));
        assertEquals(false, response.getData().get(WebRequest.OutputParams.REGEX_RESULT_IS_VALID.toString()));
        assertEquals(2, response.getData().get(WebRequest.OutputParams.REGEX_RESULT_NOT_VALID_INDEX.toString()));
    }
    
    @Test
	@DisplayName("fail with error invoke")
    void failWithErrorInvoke() {
        String url = "url";
        String method = null;
        Integer responseExpected = HttpStatus.OK.value();
        Object[] headers = new Object[] {};
        Object[] regexCheck = new Object[] {};
        
        String errorMessage = "error";

        Map<String, Object> params = new HashMap<>();
        params.put(Params.URL.toString(), url);
        params.put(Params.METHOD.toString(), method);
        params.put(Params.BODY.toString(), null);
        params.put(Params.HEADERS.toString(), headers);
        params.put(Params.RESPONSE_CODE_EXPECTED.toString(), responseExpected);
        params.put(Params.REGEX_EXTRA_CHECK.toString(), regexCheck);

        Mockito.when(restTemplate.exchange(
                Mockito.eq(url),
                Mockito.eq(HttpMethod.GET),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenThrow(new RestClientException(errorMessage));

        ControlResponse response = webRequest.execute(params);

        Mockito.verify(restTemplate).exchange(
                Mockito.eq(url),
                Mockito.eq(HttpMethod.GET),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        );

        assertTrue(response.getStatus().isWarn());
        assertEquals(-1, response.getData().get(WebRequest.OutputParams.STATUS_CODE.toString()));
        assertEquals(responseExpected, response.getData().get(WebRequest.OutputParams.RESPONSE_CODE_EXPECTED.toString()));
    }
    
    @Test
	@DisplayName("Coverage1")
    void coverage1() {
    	WebRequest webRequest = new WebRequest();
    	
		assertNotNull(webRequest.toString());
    }
}
