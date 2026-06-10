const express = require('express');
const cors    = require('cors');
const Groq    = require('groq-sdk');

const app  = express();
app.use(cors());
app.use(express.json());

const groq = new Groq({ apiKey: process.env.GROQ_API_KEY });

const CATEGORY_PROMPTS = {
  Storstad: 'svenska storstäder och levande stadsmiljöer med kultur, nöjen och shopping',
  Natur:    'naturupplevelser, nationalparker och naturskön natur i Sverige',
  Strand:   'strandorter, skärgård och havsnära destinationer i Sverige'
};

app.post('/suggest', async (req, res) => {
  const { category, from } = req.body;

  if (!category || !CATEGORY_PROMPTS[category]) {
    return res.status(400).json({ error: 'Ogiltig kategori' });
  }

  const fromText = from ? `från ${from}` : 'i Sverige';
  const prompt =
    `Du är en tågreseexpert i Sverige. En resenär reser ${fromText} och söker ` +
    `${CATEGORY_PROMPTS[category]} som är lätt att nå med tåg.\n\n` +
    `Ge exakt 3 destinationsförslag. Svara ENBART med en giltig JSON-array, ingen annan text:\n` +
    `[{"namn":"...","beskrivning":"Max 2 meningar.","restid":"Ca X tim med tåg"},` +
    `{"namn":"...","beskrivning":"Max 2 meningar.","restid":"Ca X tim med tåg"},` +
    `{"namn":"...","beskrivning":"Max 2 meningar.","restid":"Ca X tim med tåg"}]`;

  try {
    const completion = await groq.chat.completions.create({
      model: 'llama-3.3-70b-versatile',
      messages: [{ role: 'user', content: prompt }],
      temperature: 0.75,
      max_tokens: 700
    });

    const text      = completion.choices[0].message.content.trim();
    const jsonMatch = text.match(/\[[\s\S]*?\]/);
    if (!jsonMatch) throw new Error('AI svarade inte med JSON');

    const suggestions = JSON.parse(jsonMatch[0]);
    res.json({ suggestions });
  } catch (e) {
    console.error('Groq error:', e.message);
    res.status(500).json({ error: 'Kunde inte hämta förslag. Försök igen.' });
  }
});

app.get('/health', (_req, res) => res.json({ ok: true }));

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`MiniPrisTåget backend kör på port ${PORT}`));
