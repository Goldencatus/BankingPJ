import { Link } from 'react-router-dom'

// 존재하지 않는 경로에서 사용 가능한 시작 화면으로 안내한다.
export function NotFoundPage() {
  return <main className="status-page"><div className="status-card"><h1>페이지를 찾을 수 없습니다</h1><p>주소를 확인하거나 Dashboard로 이동해 주세요.</p><Link to="/dashboard">Dashboard로 이동</Link></div></main>
}
