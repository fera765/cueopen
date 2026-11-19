#!/usr/bin/env python3
"""Gera app/src/main/assets/bots/grokbots.json a partir do catálogo público do Grok Bot Marketplace.

Fonte: github.com/kydlikebtc/awesome-grokbot (catalog.json; cada linha declara a licença; aqui só entram
as linhas CC0-1.0 marcadas como oficiais — os bots publicados pelo time do Grok Bot no marketplace).
O marketplace não publica as instruções internas dos bots, só nome, autor, categoria e descrição; o app monta
as instruções do agente a partir dessa descrição e o usuário revisa antes de salvar.

Uso: python3 tools/bots/build-bots.py <catalog.json> app/src/main/assets/bots/grokbots.json
"""
import json
import sys

PT = {
    "beta-adoption-watcher": "Veja quem está mesmo usando a funcionalidade nova: acompanha o uso e mostra quais clientes aderiram, para o time dar seguimento.",
    "bug-reproduction": "Pega o relato, refaz o mesmo caminho no ambiente de testes, captura a falha e entrega um pacote de reprodução confiável para os desenvolvedores.",
    "call-faq-miner": "Mantém o material de apoio em dia a partir de chamadas reais: registra perguntas, marca o momento das respostas e aponta a gravação de origem.",
    "cloud-agent-orchestrator": "Mantém várias execuções de agentes andando sem precisar vigiar cada uma: inicia, acompanha, cobra o que travou e resume o resultado.",
    "docs-auditor": "Compara a central de ajuda e as notas internas com o que foi lançado de fato, aponta páginas desatualizadas e rascunha a correção.",
    "feature-request-tracker": "Nunca perca \"quem pediu isso\": garimpa conversas e chamadas numa lista viva ligada aos clientes, com o histórico real de demanda.",
    "playtest-operator": "Testa na força bruta o caminho do produto quando a API não basta: usa a interface, captura falhas e devolve um relatório enxuto.",
    "product-feedback-analyst": "Coleta e agrupa feedback das fontes conectadas, pesa evidência contra urgência e rascunha a visão priorizada.",
    "product-performance": "Entra nas ferramentas de observabilidade, analisa os flamegraphs e volta com os pontos críticos e um resumo curto.",
    "prototype-builder": "Do pedido a algo clicável, rápido: escreve o código e volta com uma captura de tela e uma URL para testar.",
    "security-questionnaire-filler": "Entra nos portais de segurança de fornecedores, puxa respostas da sua central de confiança e de RFPs anteriores e rascunha cada campo para revisão.",
    "marketing-calendar-owner": "Mantém em sincronia os calendários de conteúdo, lançamentos e eventos, regionais e globais, sem cobrança semanal.",
    "newsletter-writer": "Reúne o que foi lançado, conquistas e agenda, escreve a edição no seu estilo e deixa pronta para você enviar.",
    "personal-site-builder": "Monta um site pessoal a partir de uma descrição, resolve problemas de domínio e deixa um ponto de partida no ar.",
    "presentation-designer": "Cria apresentações na sua identidade visual a partir do seu modelo e devolve um link editável, não um arquivo estático.",
    "account-health": "Veja riscos e oportunidades antes da reunião trimestral: lê uso e sinais da sua carteira de clientes e vira uma lista clara de atenção.",
    "account-manager": "Prepara cada reunião com transcrições, notas, CRM e conversas, rascunha o retorno e mantém o contexto de uma reunião para a outra.",
    "account-research-specialist": "Classifica contas antes do contato: puxa dados do CRM e sinais ao vivo, pontua aderência e calor e monta um dossiê por conta.",
    "community-operations-manager": "Mantém o programa de embaixadores girando: avalia inscrições, organiza mensagens de vários canais e rascunha o relacionamento com cadência.",
    "compelling-events-monitor": "Acompanha publicações de lideranças sobre prêmios, lançamentos e contratações e resume os que valem uma abordagem.",
    "crm-operations-manager": "Mantém o funil limpo: cuida da higiene do CRM e do organograma antes e depois das reuniões, sem revisão manual.",
    "deal-desk-coordinator": "Rascunha notas internas de negociação com base em e-mails, CRM e chamadas, e registra no CRM depois da sua aprovação.",
    "deck-updater": "Saia da reunião com o slide já atualizado: ajusta sua apresentação a partir das notas de descoberta, com os próximos passos.",
    "enablement-fulfillment-specialist": "Atende o \"me manda as gravações\" sem garimpar: acha as gravações, monta resumos de uma página, sobe no Drive e rascunha a resposta com os links.",
    "event-guest-screener": "Enche o evento com as pessoas certas: pontua inscrições contra o seu perfil de cliente ideal e aprova em lote as mais aderentes.",
    "linkedin-campaign-manager": "Cuida da consistência do funil de geração de leads (anúncios, formulários, follow-up e UTMs); rascunha campanhas para aprovação.",
    "meeting-prep-buddy": "Monta o material de preparação com agenda, notas, CRM e conversas: quem estará na reunião, último contato e pendências.",
    "merch-fulfillment-operator": "Faz o contato, acompanha o formulário de resgate, pede sua aprovação no chat para cada pedido e envia os brindes.",
    "pipeline-analyst": "Chegue à reunião de funil com uma visão limpa: revisa CRM e planilhas, aponta negócios parados e riscos e entrega um placar da semana.",
    "prospecting-plan-builder": "Monta a carteira da semana: levanta contatos, completa e-mail e celular e escreve uma planilha pronta para a prospecção.",
    "renewal-desk-operator": "Monta um pacote de 90 dias por conta com uso, chamados, chamadas e CRM, rascunha a nota comercial e lembra o responsável.",
    "sales-call-coach-s817": "Saia de cada chamada com lição de casa: revisa as gravações e deixa orientações marcadas no tempo sobre descoberta, objeções e postura, com uma nota.",
    "sales-outbound": "Pesquisa contas durante a noite, pontua contatos por intenção, rascunha e-mail e mensagem no seu estilo e deixa para revisão.",
    "social-media-manager": "Estuda seu histórico de publicações, rascunha quando algo relevante é lançado e deixa o post pronto para você publicar.",
    "ticket-triage-specialist": "Esvazia a fila sem você viver nela: acompanha o suporte com regularidade, só rascunha respostas e fica quieto quando está tudo em dia.",
    "contract-desk": "Veja a semana de contratos de relance: resume por etapa e responsável, extrai termos-chave e aponta revisões travadas.",
    "expense-manager": "Monta o resumo semanal de despesas a partir da sua ferramenta e planilhas, registra recibos que chegam por e-mail e cobra os que faltam.",
    "invoice-coordinator": "Não deixa nota fiscal parada: encaminha, concilia o que puder, acompanha os valores por fornecedor e avisa o responsável quando precisa de alguém.",
    "paid-media": "Puxa os dados ao vivo de canais e campanhas, sugere a realocação dentro do orçamento do mês e espera sua aprovação antes de mudar algo.",
    "paid-media-creative-strategist": "Encontra cedo os criativos vencedores, escreve uma hipótese clara do porquê funcionam e propõe o próximo teste. Sem métricas inventadas.",
    "vendor-portal-operator": "Cuida de renovações, licenças e compras em portais sem API: repete o mesmo caminho toda semana e volta só com as exceções.",
    "apartment-scout": "Filtra anúncios novos de imóveis pelos seus critérios, pede horários de visita por e-mail e se candidata aos que se encaixam (com sua aprovação).",
    "subscription-cleaner": "Corta o que você esqueceu: junta recibos e newsletters, sugere o que cancelar e descadastra o que você aprovar.",
    "travel-coordinator": "Segura a melhor opção antes que expire: compara voos e hotéis com as suas regras, confirma antes de reservar e entrega o roteiro na agenda.",
    "competitive-intelligence-analyst": "Monitora à noite os lançamentos da concorrência e audita seu site atrás de criativos cansados e mensagens desatualizadas.",
    "seo-aeo-auditor": "Acompanha palavras-chave, problemas técnicos, respostas de IA e concorrentes num só lugar e aponta o que vale corrigir no site.",
    "calendar-coordinator": "Coloca as pessoas na mesma sala: agenda entre calendários e cobra as reservas que ninguém tem tempo de cobrar.",
    "chief-of-staff-sc0a": "Varre conversas, e-mail, agenda e notas de reunião e conta o que é novo e o que se liga aos seus objetivos.",
    "daily-briefing-writer": "Comece o dia com informação boa, não ruído: entrega um resumo diário enxuto só com as notícias que importam para você.",
    "executive-assistant-sf81": "Entrega um resumo pela manhã e um resumo automático de contexto sempre que você entra numa conversa nova.",
    "hiring-screener": "Entreviste só os melhores: pontua candidaturas ou testes contra um critério definido e entrega uma avaliação pronta para o sistema de recrutamento.",
    "inbox-manager": "Organiza a caixa de entrada por categorias, destaca o urgente e o travado e rascunha respostas. Todo envio espera por você.",
    "internal-communications-manager": "Rascunha textos claros, no tom certo, a partir do seu contexto real, adaptados a cada público e canal. Só para revisão: nunca envia sozinho.",
    "onboarding-manager": "Monta o checklist, separa os documentos certos, responde as dúvidas do primeiro dia e encaminha o resto para quem sabe responder.",
    "status-report-writer": "Cuida das pendências para nada escapar: junta itens abertos de documentos, reuniões e conversas numa lista viva e num resumo matinal.",
    "talent-scout-sac3": "Conduz da triagem à proposta: encontra candidatos, rascunha a abordagem no seu estilo e pula quem já está no sistema de recrutamento.",
}

CATEGORY = {
    "coding-shipping": "Produto e engenharia",
    "content-publishing": "Conteúdo e marketing",
    "customer-sales": "Vendas e clientes",
    "finance-ops": "Finanças e operações",
    "personal-admin": "Vida pessoal",
    "research-briefings": "Pesquisa e resumos",
    "teams-handoffs": "Equipe e produtividade",
    "inbox-calendar": "E-mail e agenda",
}


def main() -> None:
    src, out = sys.argv[1], sys.argv[2]
    entries = json.load(open(src, encoding="utf-8"))["entries"]
    bots = []
    for e in entries:
        if not e.get("official") or e.get("license") != "CC0-1.0":
            continue
        slug = e["slug"]
        bots.append({
            "id": slug,
            "name": e["name"],
            "category": e["category"],
            "categoryLabel": CATEGORY.get(e["category"], e["category"]),
            "summary": PT.get(slug, e["summary"]),
            "summaryEn": e.get("official_summary") or e["summary"],
            "tags": e.get("tags", []),
            "author": (e.get("author") or {}).get("name", ""),
            "url": e.get("import", ""),
        })
    missing = [b["id"] for b in bots if b["id"] not in PT]
    if missing:
        print("sem tradução:", ", ".join(missing), file=sys.stderr)
    bots.sort(key=lambda b: (b["categoryLabel"], b["name"]))
    data = {
        "source": "Grok Bot Marketplace (x.ai/bot/marketplace) via github.com/kydlikebtc/awesome-grokbot — linhas CC0-1.0",
        "count": len(bots),
        "bots": bots,
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=0)
    print(f"{len(bots)} bots → {out}")


if __name__ == "__main__":
    main()
