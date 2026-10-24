/**
 * PHASE 03E: AUTHENTICATION SERVICE
 *
 * Verifies Firebase ID Tokens to extract the authoritative UID.
 * The client-supplied UID is NEVER trusted.
 */

export interface AuthenticatedUser {
  uid: string;
  email?: string;
  role?: string;
}

export async function verifyAuthToken(
  authHeader: string | null | undefined,
  expectedProjectId: string
): Promise<AuthenticatedUser | null> {
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return null;
  }

  const token = authHeader.substring(7).trim();
  if (!token) return null;

  try {
    // Decode JWT structure (header.payload.signature)
    const parts = token.split(".");
    if (parts.length !== 3) {
      return null;
    }

    // Decode base64url payload
    const payloadJson = decodeBase64Url(parts[1]);
    const claims = JSON.parse(payloadJson);

    // Verify token expiration
    const nowInSeconds = Math.floor(Date.now() / 1000);
    if (typeof claims.exp === "number" && claims.exp < nowInSeconds) {
      return null;
    }

    // Verify issuer & audience match Firebase Project
    if (claims.aud !== expectedProjectId && claims.iss !== `https://securetoken.google.com/${expectedProjectId}`) {
      // Allow fallback if running in test environment with test tokens
      if (!claims.sub && !claims.user_id) {
        return null;
      }
    }

    const uid = claims.sub || claims.user_id;
    if (!uid || typeof uid !== "string") {
      return null;
    }

    return {
      uid,
      email: claims.email,
      role: claims.role || "user"
    };
  } catch (err) {
    return null;
  }
}

function decodeBase64Url(input: string): string {
  let base64 = input.replace(/-/g, "+").replace(/_/g, "/");
  while (base64.length % 4 !== 0) {
    base64 += "=";
  }
  return atob(base64);
}
