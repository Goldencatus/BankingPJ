// 인증 후 처음 표시되는 기본 대시보드 화면을 제공한다.
export function DashboardPage() {
  return <section className="page-stack"><p className="eyebrow">OVERVIEW</p><h1>Dashboard</h1><p className="page-lead">계좌와 최근 금융 활동을 연결할 준비가 되었습니다.</p><div className="dashboard-grid"><article className="summary-card"><span>Accounts</span><strong>계좌 조회 준비</strong><p>STEP 12에서 실제 계좌 API를 연결합니다.</p></article><article className="summary-card"><span>Transfer</span><strong>안전한 이체</strong><p>서버의 멱등성 키 정책과 연결할 수 있습니다.</p></article><article className="summary-card"><span>Transactions</span><strong>원장 기반 내역</strong><p>페이지 조회 화면을 연결할 수 있습니다.</p></article></div></section>
}
