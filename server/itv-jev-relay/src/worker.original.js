
function reply(data, status = 200) {
  return Response.json(data, {
    status,
    headers: { "Cache-Control": "no-store" }
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // 서버 정상 작동 확인
    if (request.method === "GET" && url.pathname === "/health") {
      return reply({ status: "ok" });
    }

    if (url.pathname !== "/api/jev/evaluate") {
      return reply({ error: "Not found" }, 404);
    }

    if (request.method !== "POST") {
      return reply({ error: "Method not allowed" }, 405);
    }

    if (!env.JEV_API_KEY || !env.ITV_TEST_TOKEN) {
      return reply({ error: "Server not configured" }, 503);
    }

    // 임시 개발용 인증
    if (request.headers.get("Authorization") !==
        `Bearer ${env.ITV_TEST_TOKEN}`) {
      return reply({ error: "Unauthorized" }, 401);
    }

    if (!(request.headers.get("Content-Type") || "")
        .toLowerCase().includes("application/json")) {
      return reply({ error: "JSON required" }, 415);
    }

    let data;
    try {
      const raw = await request.text();
      if (raw.length > 2048) {
        return reply({ error: "Request too large" }, 413);
      }
      data = JSON.parse(raw);
    } catch {
      return reply({ error: "Invalid JSON" }, 400);
    }

    const fields = [
      data?.session_minutes,
      data?.returns_10m,
      data?.bedtime_passed_minutes
    ];

    if (!fields.every(v => Number.isInteger(v) && v >= 0 && v <= 1440)) {
      return reply({ error: "Invalid input" }, 400);
    }

    const payload = {
      model: "jev-latest",
      state: {
        current_session_minutes: data.session_minutes,
        returns_in_last_10_minutes: data.returns_10m,
        minutes_after_bedtime_goal: data.bedtime_passed_minutes
      },
      questions: {
        intervene: {
          type: "noul",
          instructions:
            "Based on the provided short-form viewing pattern, " +
            "would suggesting a voluntary short break now be appropriate? " +
            "Consider prolonged viewing, repeated returns, and bedtime."
        }
      }
    };

    try {
      const response = await fetch(
        "https://api.typesafe.ai/v1/systemone",
        {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${env.JEV_API_KEY}`,
            "Content-Type": "application/json"
          },
          body: JSON.stringify(payload),
          signal: AbortSignal.timeout(8000)
        }
      );

      if (!response.ok) {
        return reply({ error: "Jev request failed" }, 502);
      }

      const result = await response.json();
      const probability = result.answers?.intervene?.noul;

      if (typeof probability !== "number" ||
          probability < 0 || probability > 1) {
        return reply({ error: "Invalid Jev response" }, 502);
      }

      return reply({
        probability,
        model: result.model,
        input_tokens: result.usage?.input_tokens
      });
    } catch {
      return reply({ error: "Jev unavailable" }, 502);
    }
  }
};
