interface PlaceholderPageProps { title: string; description: string }

// 후속 STEP에서 실제 API 기능을 연결할 보호 화면의 기본 구조를 제공한다.
export function PlaceholderPage({ title, description }: PlaceholderPageProps) {
  return <section className="page-stack"><p className="eyebrow">BANKINGPJ</p><h1>{title}</h1><div className="empty-card"><strong>화면 준비 완료</strong><p>{description}</p></div></section>
}
