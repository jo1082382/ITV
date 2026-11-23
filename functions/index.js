const { onRequest } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");

initializeApp();

const KAKAO_USER_ME_URL = "https://kapi.kakao.com/v2/user/me";
const NAVER_USER_ME_URL = "https://openapi.naver.com/v1/nid/me";

async function verifyKakaoAccessToken(accessToken) {
  const response = await fetch(KAKAO_USER_ME_URL, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/x-www-form-urlencoded;charset=utf-8",
    },
  });

  if (!response.ok) {
    const bodyText = await response.text().catch(() => "");
    throw new Error(`KAKAO_VERIFY_FAILED (${response.status}): ${bodyText.slice(0, 200)}`);
  }

  const data = await response.json();
  if (!data || data.id === undefined || data.id === null) {
    throw new Error("KAKAO_INVALID_PROFILE");
  }

  const kakaoAccount = data.kakao_account || {};
  const profile = kakaoAccount.profile || {};
  const rawId = String(data.id).trim();
  if (!rawId) {
    throw new Error("KAKAO_EMPTY_ID");
  }

  return {
    uid: `kakao_${rawId}`,
    provider: "KAKAO",
    displayName: profile.nickname || "카카오 사용자",
    email: kakaoAccount.email || null,
    photoUrl: profile.profile_image_url || profile.thumbnail_image_url || null,
  };
}

async function verifyNaverAccessToken(accessToken) {
  const response = await fetch(NAVER_USER_ME_URL, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
  });

  if (!response.ok) {
    const bodyText = await response.text().catch(() => "");
    throw new Error(`NAVER_VERIFY_FAILED (${response.status}): ${bodyText.slice(0, 200)}`);
  }

  const data = await response.json();
  if (!data || data.resultcode !== "00" || !data.response || !data.response.id) {
    throw new Error(`NAVER_INVALID_PROFILE (${data?.resultcode || "unknown"})`);
  }

  const profile = data.response;
  const rawId = String(profile.id).trim();
  if (!rawId) {
    throw new Error("NAVER_EMPTY_ID");
  }

  return {
    uid: `naver_${rawId}`,
    provider: "NAVER",
    displayName: profile.nickname || profile.name || "네이버 사용자",
    email: profile.email || null,
    photoUrl: profile.profile_image || null,
  };
}

/**
 * Verifies a Kakao or Naver OAuth access token directly with the provider's official API
 * and returns a Firebase Custom Token so the client shares Firebase Auth (`request.auth.uid`)
 * and Cloud Firestore ownership rules identically with Google Sign-In.
 *
 * POST JSON: { "provider": "KAKAO" | "NAVER", "accessToken": "<oauth_access_token>" }
 */
exports.exchangeSocialToken = onRequest(
  {
    region: "asia-northeast3",
    maxInstances: 20,
    timeoutSeconds: 15,
    memory: "256MiB",
    cors: false,
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: "METHOD_NOT_ALLOWED" });
      return;
    }

    const body = req.body && typeof req.body === "object" ? req.body : {};
    const provider = typeof body.provider === "string" ? body.provider.trim().toUpperCase() : "";
    const accessToken = typeof body.accessToken === "string" ? body.accessToken.trim() : "";

    if (!provider || (provider !== "KAKAO" && provider !== "NAVER")) {
      res.status(400).json({ error: "INVALID_PROVIDER" });
      return;
    }
    if (!accessToken || accessToken.length < 10 || accessToken.length > 4096) {
      res.status(400).json({ error: "INVALID_ACCESS_TOKEN" });
      return;
    }

    let verified;
    try {
      verified =
        provider === "KAKAO"
          ? await verifyKakaoAccessToken(accessToken)
          : await verifyNaverAccessToken(accessToken);
    } catch (err) {
      logger.error("Social provider verification failed", {
        provider,
        errCode: err?.code || "VERIFY_ERROR",
        errDetail: err?.message || String(err),
      });
      res.status(401).json({
        error: "SOCIAL_TOKEN_VERIFICATION_FAILED",
        message: "소셜 로그인 토큰 검증에 실패했습니다.",
      });
      return;
    }

    try {
      const auth = getAuth();

      // Ensure Firebase Auth user exists with displayName/photoUrl without setting email
      // (avoiding email-already-exists collisions when a user tests multiple providers with the same email).
      try {
        await auth.updateUser(verified.uid, {
          displayName: verified.displayName || undefined,
          photoURL: verified.photoUrl || undefined,
        });
      } catch (err) {
        if (err && err.code === "auth/user-not-found") {
          await auth.createUser({
            uid: verified.uid,
            displayName: verified.displayName || undefined,
            photoURL: verified.photoUrl || undefined,
          });
        } else {
          logger.warn("Non-fatal Auth user metadata sync warning", {
            uid: verified.uid,
            errCode: err?.code,
            errDetail: err?.message,
          });
        }
      }

      const customToken = await auth.createCustomToken(verified.uid, {
        socialProvider: verified.provider,
      });

      res.status(200).json({
        customToken,
        uid: verified.uid,
        provider: verified.provider,
        displayName: verified.displayName,
        email: verified.email,
        photoUrl: verified.photoUrl,
      });
    } catch (err) {
      logger.error("Firebase custom token minting failed", {
        provider,
        uid: verified.uid,
        errCode: err?.code || "CUSTOM_TOKEN_ERROR",
        errDetail: err?.message || String(err),
      });
      res.status(500).json({
        error: err?.code || "CUSTOM_TOKEN_MINT_FAILED",
        message: `Firebase 커스텀 토큰 발급 실패: ${err?.code || err?.message || "권한 확인 필요"}`,
      });
    }
  }
);
