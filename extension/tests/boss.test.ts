import { describe, expect, it } from "vitest";
import { whitelist } from "../lib/capture";
import { mapDetail, internshipLabels } from "../lib/boss";

// Field names match boss-helper 0.5.0 BossZpDetailData, not an invented API.
const detail = { jobInfo: { encryptId: "encrypted-A", jobName: "中级java开发工程师（金融方向）",
  locationName: "北京", address: "北京市通州区合景中心", salaryDesc: "14-15K",
  degreeName: "本科", experienceName: "3-5年", showSkills: ["Java", "MySQL", "Spring"],
  postDescription: "岗位职责\n参与Java系统开发与优化\n任职要求\n熟悉银行业务" },
  brandComInfo: { brandName: "中电金信" } };

describe("BOSS detail bridge contract", () => {
  it("preserves internship labels and maps only unambiguous explicit units", () => {
    expect(internshipLabels("5天/周 3个月")).toEqual({ internshipDays: 5, internshipMonths: 3, duration: "5天/周 3个月" });
    expect(internshipLabels("5天/周 4天/周 3个月 6个月")).toMatchObject({ internshipDays: null, internshipMonths: null });
    expect(internshipLabels("3-5年")).toMatchObject({ internshipDays: null, internshipMonths: null });
    expect(whitelist({title: "Java", description: "JD", internshipDays: 5, internshipMonths: 3})?.internshipMonths).toBe(3);
  });
  it("maps the reference plugin's actual detail shape", () => {
    expect(mapDetail(detail, "encrypted-A")).toMatchObject({ sourceJobId: "encrypted-A",
      title: detail.jobInfo.jobName, description: detail.jobInfo.postDescription,
      city: "北京", location: "北京市通州区合景中心", salary: "14-15K", company: "中电金信",
      education: "本科", experience: "3-5年", skills: ["Java", "MySQL", "Spring"] });
  });
  it("rejects the old detail when the selected card identifies another job", () => {
    expect(mapDetail(detail, "encrypted-B")).toBeNull();
    expect(mapDetail({ jobInfo: { jobName: "Java", postDescription: "JD" } }, "encrypted-B")).toBeNull();
  });
  it("leaves missing fields unknown without defaults from unrelated list data", () => {
    expect(mapDetail({ jobInfo: { jobName: "Java", postDescription: "JD" } }, "")).toMatchObject({
      city: "", location: "", education: "", experience: "", skills: [], sourceJobId: "" });
    expect(mapDetail({ jobInfo: { jobName: "Java" } }, "")).toBeNull();
  });
  it("preserves all permitted detail fields, including skills and source ID", () => {
    const job = whitelist({ title: "Java", description: "完整 JD", city: "北京",
      location: "北京市通州区合景中心", education: "本科", experience: "3-5年",
      skills: ["Java", "MySQL", 7], sourceJobId: "encrypted-A" });
    expect(job).toMatchObject({ city: "北京", location: "北京市通州区合景中心",
      education: "本科", experience: "3-5年", skills: ["Java", "MySQL"], sourceJobId: "encrypted-A" });
  });
});
