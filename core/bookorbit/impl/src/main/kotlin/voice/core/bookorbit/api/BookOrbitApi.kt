package voice.core.bookorbit.api

import kotlinx.serialization.json.JsonObject
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

internal interface BookOrbitApi {
  @GET("server-identity") suspend fun identity(): Response<ServerIdentity>

  @GET("auth/setup-status") suspend fun setupStatus(): SetupStatus

  @GET("auth/login-options") suspend fun loginOptions(): LoginOptions

  @GET("app-info") suspend fun appInfo(): AppInfo

  @POST("auth/login") suspend fun login(@Body request: LoginRequest): LoginResponse

  @POST("auth/refresh") fun refresh(@Body request: RefreshRequest): Call<NativeTokens>

  @POST("auth/logout") suspend fun logout(@Body request: RefreshRequest): Response<Unit>

  @GET("auth/me") suspend fun me(): BoUser

  @GET("libraries") suspend fun libraries(): List<BoLibrary>

  @POST("libraries/{id}/books")
  suspend fun books(
    @Path("id") libraryId: Long,
    @Body query: JsonObject,
  ): BookPage

  @GET("audiobooks/{id}/manifest") suspend fun manifest(@Path("id") bookId: Long): AudiobookManifest

  @GET("books/{id}") suspend fun statusDetail(@Path("id") bookId: Long): BookStatusDetail

  @GET("audiobooks/{id}/playback-state")
  suspend fun playbackStateResponse(@Path("id") bookId: Long): Response<PlaybackState>

  suspend fun playbackState(bookId: Long): PlaybackState? {
    val response = playbackStateResponse(bookId)
    if (!response.isSuccessful) throw retrofit2.HttpException(response)
    return response.body()
  }

  @PUT("audiobooks/{id}/playback-state")
  suspend fun updatePlaybackState(
    @Path("id") bookId: Long,
    @Body update: PlaybackUpdate,
  ): Response<PlaybackState>

  @PATCH("books/{id}/status")
  suspend fun updateStatus(
    @Path("id") bookId: Long,
    @Body update: StatusUpdate,
  ): Response<BookReadStatus>

  @GET("audiobooks/{id}/bookmarks")
  suspend fun bookmarks(@Path("id") bookId: Long): List<BookOrbitBookmark>

  @POST("audiobooks/{id}/bookmarks")
  suspend fun createBookmark(
    @Path("id") bookId: Long,
    @Body request: BookmarkRequest,
  ): Response<BookOrbitBookmark>

  @PATCH("audiobooks/{id}/bookmarks/{bookmark}")
  suspend fun updateBookmark(
    @Path("id") bookId: Long,
    @Path("bookmark") bookmarkId: String,
    @Body update: BookmarkUpdate,
  ): Response<BookOrbitBookmark>

  @DELETE("audiobooks/{id}/bookmarks/{bookmark}")
  suspend fun deleteBookmark(
    @Path("id") bookId: Long,
    @Path("bookmark") bookmarkId: String,
  ): Response<Unit>

  @POST("books/files/{id}/sessions")
  suspend fun session(
    @Path("id") fileId: Long,
    @Body session: SessionRequest,
  ): Response<Unit>
}
