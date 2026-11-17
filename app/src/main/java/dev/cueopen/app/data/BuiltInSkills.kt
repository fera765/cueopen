package dev.cueopen.app.data

/** Skills nativas do app. Hoje só a Skill creator; as demais vêm da biblioteca do usuário (criadas ou instaladas por link). */
object BuiltInSkills {
    val skillCreator = Skill(
        id = "skill-creator", builtIn = true, icon = "wand",
        name = "Skill creator",
        description = "Cria novas skills a partir de uma conversa, salvando-as na sua biblioteca.",
        instructions = """
            Quando o usuário quiser criar uma skill: faça uma entrevista breve (o que ela faz, quando usar, quais os passos)
            e então chame create_skill com nome, descrição curta (quando usar) e instruções detalhadas, escritas como passo a passo.
            Se o usuário mandar um link (GitHub, SKILL.md, .zip), não recrie à mão: use skill_install com o link.
        """.trimIndent(),
    )

    val all = listOf(skillCreator)

    /** Skills nativas que existiram em versões antigas e foram retiradas: saem também das instalações existentes. */
    val retiredIds = setOf("skill-slides", "skill-website", "skill-wide-research", "skill-playbook", "skill-mobile-app",
        "skill-image", "skill-context-efficiency", "skill-delegate-reading")
}
