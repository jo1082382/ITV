package com.example.itv.domain.auth

/** 로그인 방식 구분. Guest는 서버 인증 없이 기기 내부에서만 식별. */
enum class LoginType { GUEST, GOOGLE }

/** 사용자 권한 구분 — SDS M-01: 게스트/유저/관리자 */
enum class UserRole { USER, ADMIN }

/** 로그인된 사용자 정보. ownerId는 기존 data.local의 owners 테이블 ownerId와 연결됨. */
data class AuthUser(
    val ownerId: String,
    val loginType: LoginType,
    val role: UserRole,
    val displayName: String?,
    val email: String?,
    val firebaseUid: String?   // Guest는 null
)

/** 현재 로그인 상태. 앱 시작 시 "아직 모름" / "로그인됨" / "로그인 안됨" 구분 필요 */
sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val user: AuthUser) : AuthState
}

/** 사용자가 계정 선택 창을 닫은 경우. 오류가 아니므로 화면에서 에러 문구를 띄우지 않는다. */
class LoginCancelledException : Exception("로그인이 취소되었습니다.")

/** 로그인 과정의 실제 동작 계약. 실제 구현(FirebaseAuth 호출)은 infrastructure에 위치. */
interface AuthRepository {
    /** 앱 시작 시 저장된 로그인(구글/게스트)을 복원. 없으면 null */
    suspend fun currentUser(): AuthUser?

    /** Google 로그인 — 성공 시 AuthUser 반환, 취소 시 LoginCancelledException, 그 외 실패는 예외 */
    suspend fun signInWithGoogle(): AuthUser

    /** 게스트로 시작 — 이전 게스트가 있으면 재사용, 없으면 새로 발급 (서버 통신 없음) */
    suspend fun signInAsGuest(): AuthUser

    /** 로그아웃 — Firebase 세션 해제 후, 계정 자료는 보관하고 비어 있는 새 게스트로 전환해 반환 */
    suspend fun signOut(): AuthUser
}

internal object AuthPolicy {
    /** 관리자 판단 기준: SDS 기준 이메일 화이트리스트 — 나중에 서버/Firestore 커스텀 클레임으로 교체 가능 */
    fun resolveRole(email: String?): UserRole =
        if (email != null && email in ADMIN_EMAILS) UserRole.ADMIN else UserRole.USER

    private val ADMIN_EMAILS = setOf<String>() // TODO: 팀 합의 후 관리자 이메일 채우기
}