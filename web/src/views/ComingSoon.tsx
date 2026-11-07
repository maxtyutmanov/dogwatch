export function ComingSoon({ title, milestone, text }: { title: string; milestone: string; text: string }) {
  return (
    <section class="card coming-soon">
      <span class="badge">Coming in {milestone}</span>
      <h2>{title}</h2>
      <p class="muted">{text}</p>
    </section>
  );
}
