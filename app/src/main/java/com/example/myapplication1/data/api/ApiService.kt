package com.example.myapplication1.data.api

import com.example.myapplication1.domain.model.ChatMessage
import com.example.myapplication1.domain.model.Contact
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    
    // 1. 登录认证
    @POST("/api/auth/login")
    suspend fun login(@Body credentials: Map<String, String>): Map<String, Any>

    // 2. 获取联系人列表 (同步 PC 端 sip-client 数据)
    @GET("/api/users")
    suspend fun getContacts(): List<Contact>

    // 3. 消息同步接口 (IM 业务流优先)
    @GET("/api/messages")
    suspend fun getMessages(): List<ChatMessage>

    @POST("/api/messages")
    suspend fun sendMessage(@Body message: ChatMessage): Map<String, Any>

    companion object {
        private const val BASE_URL = "http://10.0.2.2:8080"

        fun create(): ApiService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }
}
