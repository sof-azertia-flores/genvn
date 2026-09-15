import useSmoothedProgress from "../useSmoothedProgress";
import { useT } from "../i18n";
import type { CreationJobView } from "../types";

interface Props {
  job: CreationJobView | null;
  pending: boolean;
  /** Both default to the story-creation wording; a restructure passes its own. */
  heading?: string;
  readyHeading?: string;
}

export default function CompilationProgress({ job, pending, heading, readyHeading }: Props) {
  const tr = useT();
  const value = useSmoothedProgress(job?.id ?? "", job?.progress ?? 0, job?.status ?? "QUEUED");
  const completed = job?.status === "READY";
  const failed = job?.status === "FAILED";
  const stage = job?.stage || tr("compileWaitingStage");
  const className = completed ? "is-complete" : failed ? "is-failed" : pending ? "is-waiting" : "";
  const label = completed ? readyHeading ?? tr("compileFirstReady") : heading ?? tr("compileProgress");
  return <div className={`compile-progress-area ${className}`}>
    <div className="compile-progress-label"><span>{label}</span><b aria-hidden="true">{Math.floor(value)}<small>%</small></b></div>
    <div className="compile-progress-track" role="progressbar" aria-label={heading ?? tr("compileProgress")} aria-valuemin={0} aria-valuemax={100}
      aria-valuenow={Math.floor(value)} aria-valuetext={`${Math.floor(value)}%, ${stage}`}>
      <div className="compile-progress-fill" style={{ width: `${value}%` }} />
    </div>
    <p className="compile-progress-status" role="status"><i aria-hidden="true" />{stage}</p>
  </div>;
}
