import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  private baseUrl = environment.apiBaseUrl;

  constructor(private http: HttpClient) {}

  private getHttpHeaders() {
    return new HttpHeaders({
        'Content-Type': 'application/json'
      })
  }

  private parseParams(queryParams?: Record<string, string | number>) : HttpParams {
    let params = new HttpParams();
    if (queryParams) {
      for (const key in queryParams) {
        if (queryParams.hasOwnProperty(key)) {
          params = params.set(key, queryParams[key]);
        }
      }
    }
    return params;
  } 

  getData<T>(endpoint: string, queryParams?: Record<string, string | number>): Observable<T> {
    const params: HttpParams = this.parseParams(queryParams);
    return this.http.get<T>(`${this.baseUrl}/${endpoint}`, {
      headers: this.getHttpHeaders(),
      params: params,
    });
  }

  postData<T>(endpoint: string, data: any, queryParams?: Record<string, string | number>): Observable<T> {
    const params: HttpParams = this.parseParams(queryParams);
    return this.http.post<T>(`${this.baseUrl}/${endpoint}`, data, {
      headers: this.getHttpHeaders(),
      params: params,
    });
  }

  putData<T>(endpoint: string, data: any, queryParams?: Record<string, string | number>): Observable<T> {
    const params: HttpParams = this.parseParams(queryParams);
    return this.http.put<T>(`${this.baseUrl}/${endpoint}`, data, {
      headers: this.getHttpHeaders(),
      params: params,
    });
  }

  patchData<T>(endpoint: string, data: any, queryParams?: Record<string, string | number>): Observable<T> {
    const params: HttpParams = this.parseParams(queryParams);
    return this.http.patch<T>(`${this.baseUrl}/${endpoint}`, data, {
      headers: this.getHttpHeaders(),
      params: params,
    });
  }

  deleteData<T>(endpoint: string, queryParams?: Record<string, string | number>): Observable<T> {
    const params: HttpParams = this.parseParams(queryParams);
    return this.http.delete<T>(`${this.baseUrl}/${endpoint}`, {
      headers: this.getHttpHeaders(),
      params: params,
    });
  }
}
