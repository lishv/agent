export const apps = [
  {
    id: 'love',
    name: 'AI 恋爱大师',
    shortName: '恋爱大师',
    english: 'LOVE & CONNECTION',
    tagline: '关于心动，也关于懂你。',
    description: '从初次心动到长久相处，陪你理清感受，找到属于自己的答案。',
    greeting: '今天，有什么心事？',
    intro: '无论是心动的小秘密，还是相处中的困惑，\n都可以慢慢说，我在这里听你讲。',
    placeholder: '说说你的心事吧…',
    capabilities: ['情感倾诉', '关系建议', '约会灵感'],
    suggestions: [
      { title: '读懂心动信号', subtitle: 'TA 的这些表现，是喜欢我吗？', prompt: '我喜欢一个人，但不确定对方是否也喜欢我。可以帮我分析一下相处中的信号吗？' },
      { title: '化解相处难题', subtitle: '让沟通多一点理解，少一点误会', prompt: '我和伴侣最近总因为小事争吵，我想找到更好的沟通方式。你可以先问我一些问题吗？' },
      { title: '策划一次约会', subtitle: '把普通的一天，变成特别的回忆', prompt: '帮我策划一次轻松、有心意的约会。请先了解我的城市、预算和我们的喜好。' },
    ],
  },
  {
    id: 'manus',
    name: 'AI 超级智能体',
    shortName: '超级智能体',
    english: 'IDEAS INTO ACTION',
    tagline: '你提想法，我来行动。',
    description: '搜索信息、整理资料、制定计划。把复杂任务，交给一个得力助手。',
    greeting: '一起，把想法变成现实。',
    intro: '告诉我你想完成的任务，\n我会一步步处理，并与你分享执行进展。',
    placeholder: '描述你想完成的任务…',
    capabilities: ['自主规划', '联网搜索', '文档生成'],
    suggestions: [
      { title: '来一份调研', subtitle: '搜集信息，梳理要点与来源', prompt: '帮我搜索并整理 Vue 3 的核心特点和常见使用场景，附上信息来源。' },
      { title: '制定出行计划', subtitle: '从行程灵感，到细致安排', prompt: '帮我制定一份杭州两天一夜的旅行计划，预算 1000 元，喜欢自然景观和本地美食。' },
      { title: '生成一份文档', subtitle: '把零散想法，整理成完整内容', prompt: '帮我整理一份适合初学者的 AI 智能体学习路线，并生成 PDF 文档。' },
    ],
  },
]

export const getApp = (id) => apps.find((app) => app.id === id) || apps[0]
