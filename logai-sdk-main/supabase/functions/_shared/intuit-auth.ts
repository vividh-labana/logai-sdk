// Intuit IAM Authentication for LLM Service
// Shared module for creating offline tickets and calling Intuit LLM

// Credentials (these should ideally be in Supabase secrets)
const INTUIT_APP_ID = "Intuit.billingcomm.billing.testingmodel";
const INTUIT_APP_SECRET = "preprdxvYPGAI2U4LFPbPGC5QNqkXOCqqfo7L029";
const INTUIT_EXPERIENCE_ID = "ac6fdeea-ddc1-42fe-94e1-4ddbd9bbbc8a";
const IDENTITY_URL = "https://identityinternal-e2e.api.intuit.com/v1/graphql";
const LLM_BASE_URL = "https://llmexecution-e2e.api.intuit.com/v3";

// Cache for offline ticket
let cachedTicket: {
  token: string | null;
  userid: string | null;
  expiresAt: number;
} = {
  token: null,
  userid: null,
  expiresAt: 0,
};

/**
 * Create offline ticket for Intuit IAM authentication.
 * Caches the token to avoid creating new tickets on every call.
 */
export async function createOfflineTicket(): Promise<{ token: string; userid: string }> {
  const currentTime = Date.now() / 1000;

  // Check if we have a valid cached token (with 30-second buffer)
  if (
    cachedTicket.token &&
    cachedTicket.userid &&
    cachedTicket.expiresAt > currentTime + 30
  ) {
    console.log("♻️ Reusing cached offline ticket");
    return { token: cachedTicket.token, userid: cachedTicket.userid };
  }

  // GraphQL mutation to get offline ticket
  const graphqlQuery = `
    mutation identitySignInInternalApplicationWithPrivateAuth($input: Identity_SignInApplicationWithPrivateAuthInput!) {
      identitySignInInternalApplicationWithPrivateAuth(input: $input) {
        accessToken {
          token
          tokenType
          expiresInSeconds
        }
        refreshToken {
          token
          tokenType
          expiresInSeconds
        }
        accountContext {
          accountId
          profileId
          namespace
          pseudonymId
        }
        authorizationHeader
      }
    }
  `;

  const variables = {
    input: {
      profileId: "9341456087107863",
    },
  };

  const headers = {
    "Content-Type": "application/json",
    Authorization: `Intuit_IAM_Authentication intuit_appid=${INTUIT_APP_ID},intuit_app_secret=${INTUIT_APP_SECRET}`,
  };

  console.log("🔐 Creating new offline ticket...");

  const response = await fetch(IDENTITY_URL, {
    method: "POST",
    headers,
    body: JSON.stringify({
      query: graphqlQuery,
      variables,
    }),
  });

  if (!response.ok) {
    throw new Error(`Failed to create offline ticket: ${response.status} - ${await response.text()}`);
  }

  const data = await response.json();

  if (data.errors) {
    throw new Error(`GraphQL errors: ${JSON.stringify(data.errors)}`);
  }

  const result = data.data.identitySignInInternalApplicationWithPrivateAuth;
  const authHeader = result.authorizationHeader;
  const expiresIn = result.accessToken.expiresInSeconds;

  // Parse authorizationHeader to extract intuit_token and intuit_userid
  let intuitToken: string | null = null;
  let intuitUserid: string | null = null;

  if (authHeader.startsWith("Intuit_IAM_Authentication ")) {
    const paramsStr = authHeader.substring("Intuit_IAM_Authentication ".length);
    const params: Record<string, string> = {};

    for (const param of paramsStr.split(",")) {
      if (param.includes("=")) {
        const [key, value] = param.split("=", 2);
        params[key.trim()] = value.trim();
      }
    }

    intuitToken = params["intuit_token"] || null;
    intuitUserid = params["intuit_userid"] || null;
  }

  if (!intuitToken || !intuitUserid) {
    throw new Error("Failed to parse intuit_token or intuit_userid from authorizationHeader");
  }

  // Cache the token (API expiration minus 100s safety buffer)
  const cacheDuration = expiresIn - 100;
  cachedTicket = {
    token: intuitToken,
    userid: intuitUserid,
    expiresAt: currentTime + cacheDuration,
  };

  console.log(`✅ Offline ticket created (expires in ${expiresIn}s, cached for ${cacheDuration}s)`);

  return { token: intuitToken, userid: intuitUserid };
}

/**
 * Call Intuit's LLM service.
 */
export async function callIntuitLLM(
  prompt: string,
  modelId: string = "gpt-5-2025-08-07",
  temperature: number = 0.5,
  maxTokens: number = 10000
): Promise<string | null> {
  // Get offline ticket
  const { token: offlineToken, userid: offlineUserid } = await createOfflineTicket();

  // LLM service URL
  const url = `${LLM_BASE_URL}/${modelId}/chat/completions`;

  // Set up headers
  const headers: Record<string, string> = {
    Authorization: `Intuit_IAM_Authentication intuit_appid=${INTUIT_APP_ID},intuit_app_secret=${INTUIT_APP_SECRET}, intuit_token=${offlineToken},intuit_token_type=IAM-Ticket,intuit_userid=${offlineUserid}`,
    intuit_originating_assetalias: INTUIT_APP_ID,
    "Content-Type": "application/json",
  };

  // Add experience ID
  if (INTUIT_EXPERIENCE_ID) {
    headers["intuit_experience_id"] = INTUIT_EXPERIENCE_ID;
  }

  // Payload for LLM
  const payload = {
    messages: [
      {
        role: "user",
        content: prompt,
      },
    ],
    max_tokens: maxTokens,
    temperature,
  };

  console.log(`🤖 Calling Intuit LLM: ${modelId}`);

  const response = await fetch(url, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
  });

  console.log(`📡 Response Status: ${response.status}`);

  if (response.ok) {
    const data = await response.json();
    try {
      const content = data.choices?.[0]?.message?.content;
      return content || null;
    } catch (e) {
      console.error("Error parsing LLM response:", e);
      console.error("Raw response:", await response.text());
      return null;
    }
  } else {
    console.error(`LLM service returned status ${response.status}: ${await response.text()}`);
    return null;
  }
}

/**
 * Call LLM with system and user messages (for more complex prompts)
 */
export async function callIntuitLLMWithSystem(
  systemPrompt: string,
  userPrompt: string,
  modelId: string = "gpt-5-2025-08-07",
  temperature: number = 0.2,
  maxTokens: number = 4096
): Promise<string | null> {
  const { token: offlineToken, userid: offlineUserid } = await createOfflineTicket();

  const url = `${LLM_BASE_URL}/${modelId}/chat/completions`;

  const headers: Record<string, string> = {
    Authorization: `Intuit_IAM_Authentication intuit_appid=${INTUIT_APP_ID},intuit_app_secret=${INTUIT_APP_SECRET}, intuit_token=${offlineToken},intuit_token_type=IAM-Ticket,intuit_userid=${offlineUserid}`,
    intuit_originating_assetalias: INTUIT_APP_ID,
    "Content-Type": "application/json",
  };

  if (INTUIT_EXPERIENCE_ID) {
    headers["intuit_experience_id"] = INTUIT_EXPERIENCE_ID;
  }

  const payload = {
    messages: [
      { role: "system", content: systemPrompt },
      { role: "user", content: userPrompt },
    ],
    max_tokens: maxTokens,
    temperature,
  };

  console.log(`🤖 Calling Intuit LLM with system prompt: ${modelId}`);

  const response = await fetch(url, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
  });

  if (response.ok) {
    const data = await response.json();
    return data.choices?.[0]?.message?.content || null;
  } else {
    console.error(`LLM error ${response.status}: ${await response.text()}`);
    return null;
  }
}

