import useSmoothedProgress from "../useSmoothedProgress";
import type { CreationJobView } from "../types";

interface Props { job: CreationJobView | null; pending: boolean }

export default function CompilationProgress({ job, pending }: Props) {
  const value = useSmoothedProgress(job?.id ?? "", job?.progress ?? 0, job?.status ?? "QUEUED");
  const completed = job?.status === "READY";
  const failed = job?.status === "FAILED";
  const stage = job?.stage || "等待故事任务开始";
  const className = completed ? "is-complete" : failed ? "is-failed" : pending ? "is-waiting" : "";
  return <div className={`compile-progress-area ${className}`}>
    <div className="compile-progress-label"><span>{completed ? "第一幕准备完成" : "故事准备进度"}</span><b aria-hidden="true">{Math.floor(value)}<small>%</small></b></div>
    <div className="compile-progress-track" role="progressbar" aria-label="故事准备进度" aria-valuemin={0} aria-valuemax={100}
      aria-valuenow={Math.floor(value)} aria-valuetext={`${Math.floor(value)}%，${stage}`}>
      <div className="compile-progress-fill" style={{ width: `${value}%` }} />
    </div>
    <p className="compile-progress-status" role="status"><i aria-hidden="true" />{stage}</p>
  </div>;
}
