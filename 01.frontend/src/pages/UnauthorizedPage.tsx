import { Link } from 'react-router-dom'

// 권한이 부족한 사용자가 안전하게 이전 화면으로 이동할 수 있게 한다.
export function UnauthorizedPage() {
  return <main className="status-page"><div className="status-card"><h1>접근 권한이 없습니다</h1><p>요청한 작업을 수행할 권한이 없습니다.</p><Link to="/dashboard">Dashboard로 이동</Link></div></main>
}
