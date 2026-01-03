package com.rosan.ruto.retrofit

import dev.langchain4j.exception.HttpException
import dev.langchain4j.http.client.HttpClient
import dev.langchain4j.http.client.HttpMethod
import dev.langchain4j.http.client.HttpRequest
import dev.langchain4j.http.client.SuccessfulHttpResponse
import dev.langchain4j.http.client.sse.ServerSentEventListener
import dev.langchain4j.http.client.sse.ServerSentEventParser
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Streaming
import retrofit2.http.Url

class RetrofitHttpClient(private val api: GenericApi) : HttpClient {
    companion object {
        fun builder(): RetrofitHttpClientBuilder {
            return RetrofitHttpClientBuilder()
        }
    }

    override fun execute(request: HttpRequest): SuccessfulHttpResponse? {
        return try {
            val generic = request2Generic(request)
            val response = generic.execute()
            generic2Response(response)
        } catch (e: Exception) {
            if (e is HttpException) throw e
            throw RuntimeException(e)
        }
    }

    override fun execute(
        request: HttpRequest,
        parser: ServerSentEventParser,
        listener: ServerSentEventListener?
    ) {
        val generic = request2Generic(request)
        generic.enqueue(object : Callback<ResponseBody> {
            override fun onResponse(
                call: Call<ResponseBody?>,
                response: Response<ResponseBody?>
            ) {
                val code = response.code()
                if (!response.isSuccessful) {
                    listener?.onError(HttpException(code, response.errorBody()?.string() ?: ""))
                    return
                }

                val body = response.body()
                if (body == null) {
                    listener?.onError(HttpException(code, "Response body is null"))
                    return
                }

                try {
                    Langchain4jServerSentEventParser.parse(body.byteStream(), listener)
                    listener?.onClose()
                } catch (e: Exception) {
                    listener?.onError(e)
                }
            }

            override fun onFailure(
                call: Call<ResponseBody?>,
                t: Throwable
            ) {
                listener?.onError(t)
            }
        })
    }

    private fun generic2Response(response: Response<ResponseBody>): SuccessfulHttpResponse {
        val code = response.code()
        if (!response.isSuccessful)
            throw HttpException(code, response.errorBody()?.string() ?: "")

        val headers = response.headers()
        return SuccessfulHttpResponse.builder()
            .statusCode(code)
            .headers(headers.toMultimap())
            .body(response.body()?.string() ?: "")
            .build()
    }

    private fun request2Generic(request: HttpRequest): Call<ResponseBody> {
        val url = request.url()
        val headers = (request.headers() ?: emptyMap()).mapValues { it.value.joinToString(", ") }
        val method = request.method()
        val body = request.body()

        return when (method) {
            HttpMethod.GET -> api.get(url, headers)
            HttpMethod.POST -> api.post(url, headers, body.toRequestBody())
            HttpMethod.DELETE -> api.delete(url, headers)
        }
    }

    interface GenericApi {
        @GET
        @Streaming
        fun get(@Url url: String, @HeaderMap headers: Map<String, String>): Call<ResponseBody>

        @POST
        @Streaming
        fun post(
            @Url url: String,
            @HeaderMap headers: Map<String, String>,
            @Body body: RequestBody
        ): Call<ResponseBody>

        @PUT
        @Streaming
        fun put(
            @Url url: String,
            @HeaderMap headers: Map<String, String>,
            @Body body: RequestBody
        ): Call<ResponseBody>

        @DELETE
        @Streaming
        fun delete(@Url url: String, @HeaderMap headers: Map<String, String>): Call<ResponseBody>

        @PATCH
        @Streaming
        fun patch(
            @Url url: String,
            @HeaderMap headers: Map<String, String>,
            @Body body: RequestBody
        ): Call<ResponseBody>
    }
}