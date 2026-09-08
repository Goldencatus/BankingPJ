interface FullPageStatusProps {
  title: string
  message: string
}

// 초기화·오류 화면에서 일관된 전체 화면 상태를 표시한다.
export function FullPageStatus({ title, message }: FullPageStatusProps) {
  return (
    <main className="status-page" role="status">
      <div className="status-card">
        <span className="brand-mark" aria-hidden="true">B</span>
        <h1>{title}</h1>
        <p>{message}</p>
      </div>
    </main>
  )
}
