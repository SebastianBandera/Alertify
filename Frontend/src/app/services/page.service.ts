import { Injectable } from '@angular/core';
import { firstValueFrom, Observable } from 'rxjs';
import { ApiPagedResponse, PagedResponse } from '../data/basic.dto';

@Injectable({
    providedIn: 'root',
})
export class PageService {

    getAllPages<T>(singlePageFunction: (page: number) => Observable<ApiPagedResponse<T>>): Observable<ApiPagedResponse<T>> {
        return this.getAllPagesImpl1(singlePageFunction);
    }

    private getAllPagesImpl1<T>(singlePageFunction: (page: number) => Observable<ApiPagedResponse<T>>): Observable<ApiPagedResponse<T>> {
        return new Observable<ApiPagedResponse<T>>((observer) => {
            (async () => {
                let currentPage: number = 0;
                let totalPages: number | null = null;
                try {
                    do {
                        const response: ApiPagedResponse<T> = await firstValueFrom(singlePageFunction(currentPage));

                        observer.next(response);

                        if (totalPages == null) {
                            totalPages = response.page.totalPages;
                        }

                        currentPage++;

                    } while (totalPages != null && currentPage < totalPages);
                } catch (error) {
                    observer.error(error)
                }

                observer.complete();
            })();
        });
    }

    private getAllPagesImpl2<T>(singlePageFunction: (page: number) => Observable<PagedResponse<T>>): Observable<T[]> {
        return new Observable<T[]>((observer) => {
            let currentPage = 0;
            const fetchPage = (page: number) => {
                singlePageFunction(page).subscribe({
                    next: (response) => {
                        if (response.content.length > 0) {
                            observer.next(response.content);
                            if (currentPage < response.totalPages - 1) {
                                fetchPage(++currentPage);
                            } else {
                                observer.complete();
                            }
                        } else {
                            observer.complete();
                        }
                    },
                    error: (err) => {
                        observer.error(err);
                    },
                });
            };

            fetchPage(currentPage);
        });
    }
}
