/**
 * Network operation type definitions for Assistance Package Tools
 */

import { HttpResponseData, VisitWebResultData } from './results';

/**
 * Network operations namespace
 */
export namespace Net {
    /**
     * Perform HTTP GET request
     * @param url - URL to request
     */
    function httpGet(url: string): Promise<HttpResponseData>;

    /**
     * Perform HTTP POST request
     * @param url - URL to request
     * @param data - Data to post
     */
    function httpPost(url: string, data: string | object): Promise<HttpResponseData>;

    /**
     * Visit a webpage and extract its content
     * @param url - URL to visit
     */
    function visit(url: string): Promise<VisitWebResultData>;

    /**
     * Enhanced HTTP request with flexible options
     * @param options - HTTP request options
     */
    function http(options: {
        url: string;
        method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH' | 'HEAD' | 'OPTIONS';
        headers?: Record<string, string>;
        data?: string | object;
        timeout?: number;
        followRedirects?: boolean;
        responseType?: 'text' | 'json' | 'arraybuffer' | 'blob';
        validateStatus?: boolean;
    }): Promise<HttpResponseData>;

    /**
     * Upload file using multipart request
     * @param options - Upload options
     */
    function uploadFile(options: {
        url: string;
        method?: 'POST' | 'PUT';
        filePath: string;
        fileFieldName?: string;
        mimeType?: string;
        formFields?: Record<string, string>;
        headers?: Record<string, string>;
    }): Promise<HttpResponseData>;

    /**
     * Cookie management interface
     */
    interface CookieManager {
        /**
         * Get cookies for a domain
         * @param domain - Domain to get cookies for
         */
        get(domain: string): Promise<HttpResponseData>;

        /**
         * Set cookies for a domain
         * @param domain - Domain to set cookies for
         * @param cookies - Cookies to set (can be string or object)
         */
        set(domain: string, cookies: string | Record<string, string>): Promise<HttpResponseData>;

        /**
         * Clear cookies for a domain
         * @param domain - Domain to clear cookies for
         */
        clear(domain: string): Promise<HttpResponseData>;
    }

    /**
     * Cookie management
     */
    const cookies: CookieManager;
} 