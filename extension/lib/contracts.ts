export type Strength = "DISABLED" | "SOFT" | "HARD";
export interface Rules {
  version: number;
  algorithmVersion: string;
  config: {
    candidate: {
      availableDaysPerWeek: number | null;
      availableMonths: number | null;
      availableFrom: string | null;
    };
    criteria: {
      cities: string[];
      directions: string[];
      salaryMin: number | null;
      salaryMax: number | null;
      salaryUnit: string | null;
      excludedKeywords: string[];
    };
    modes: Record<string, Strength>;
    softNotes: string[];
  };
}
export interface Job {
  sourcePlatform: string;
  sourceJobId?: string;
  sourceUrl?: string;
  title: string;
  company?: string;
  city?: string;
  location?: string;
  salary?: string;
  education?: string;
  experience?: string;
  skills?: string[];
  internshipDays?: number | null;
  duration?: string;
  description: string;
  simulated: boolean;
  captureMethod?: string;
  direction?: string;
  internshipMonths?: number | null;
}
export interface Snapshot {
  id: string;
  job: Job;
}
export interface RuleItem {
  field: string;
  verdict: string;
  strength: string;
  candidateValue: string;
  jobValue: string;
  unit: string;
  reason: string;
  candidateSource: string;
  jobSource: string;
}
export interface Analysis {
  id: string;
  clientRequestId: string;
  jobSnapshotId: string;
  status: string;
  stale: boolean;
  reuseSource: string;
  errorCode: string | null;
  dependencies: {
    profileArtifactIdentity: { profileVersion: number };
    ruleVersion: string;
  };
  execution: { resultOrigin: string; result: Result | null } | null;
  ruleResult: { items: RuleItem[] } | null;
}
export interface Result {
  decision: string;
  overallAssessment: string;
  requirementAssessments: {
    requirementId: string;
    status: string;
    reasoning: string;
    gap: string;
    evidenceRefs: string[];
  }[];
  risks: string[];
  strengths: string[];
  recommendation: string;
  summary: string;
}
export interface ImportView {
  id: string;
  fileName: string;
  status: string;
  revision: number;
  providerMode: string;
  current: boolean;
  errorCode: string | null;
  profileArtifactId: number | null;
  contentSha256: string;
}
export interface Fact {
  factId?: string;
  factType: string;
  normalizedText: string;
  source: {
    sourceId: string;
    sourceTitle: string;
    sourceSection: string;
    sourceText: string;
  };
}
export interface Draft {
  structuredFacts: unknown[];
  retrievalFacts: Fact[];
  contextOnly: unknown[];
}
export interface CurrentProfile {
  profile: { identity: { profileVersion: number }; retrievalFacts: Fact[] };
  resume: ImportView | null;
}
export interface ChatInput {
  clientRequestId: string;
  jobSnapshotId: string;
  analysisId: string;
  question: string;
  expectedGeneration: number;
  comparisonAnalysisIds?: string[];
}
export interface Turn {
  id: string;
  clientRequestId: string;
  generation: number;
  status: string;
  errorCode: string | null;
  nextAction: string;
  question: {
    id: string;
    content: string;
    jobSnapshotId: string;
    analysisId: string;
    sequence: number;
  } | null;
  reply: {
    content: string;
    evidenceRefs: string[];
    replyToMessageId: string;
    sequence: number;
  } | null;
}
export interface History {
  generation: number;
  turns: Turn[];
  nextAfterSequence: number | null;
  hasMore: boolean;
}
export interface AnalysisInput {
  clientRequestId: string;
  jobSnapshotId: string;
  expectedProfileVersion: number;
  expectedRuleVersion: number;
}
export class Fence {
  private revision = 0;
  advance() {
    return ++this.revision;
  }
  token() {
    return this.revision;
  }
  valid(token: number) {
    return token === this.revision;
  }
}
export function wireClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
export const allowedBase = (value: string) =>
  /^http:\/\/127\.0\.0\.1:(18080|18085)\/api$/.test(value);
export function allowedRequest(method: string, path: string) {
  const [route = "", query] = path.split("?");
  if (
    path.includes("..") ||
    path.includes("#") ||
    path.includes("\\") ||
    (query && /[^\w%=&-]/.test(query))
  )
    return false;
  const patterns: Record<string, RegExp[]> = {
    GET: [
      /^\/(health|session|jobs|analyses)$/,
      /^\/jobs\/[\w-]+$/,
      /^\/analyses\/[\w-]+(?:\/(trace|evidence))?$/,
      /^\/rules\/(current|capabilities)$/,
      /^\/profile\/current$/,
      /^\/profile\/imports\/[\w-]+(?:\/facts)?$/,
      /^\/chat\/(history|messages\/[\w-]+)$/,
    ],
    POST: [
      /^\/(jobs|analyses)$/,
      /^\/rules\/validate$/,
      /^\/profile\/resume\/upload$/,
      /^\/profile\/imports\/text$/,
      /^\/profile\/imports\/[\w-]+\/(structure|confirm|retry)$/,
      /^\/chat\/messages$/,
    ],
    PUT: [/^\/rules$/, /^\/profile\/imports\/[\w-]+\/facts$/],
    DELETE: [/^\/chat$/],
  };
  return patterns[method]?.some((pattern) => pattern.test(route)) ?? false;
}
