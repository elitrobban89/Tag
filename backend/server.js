const express = require('express');
const cors    = require('cors');
const Groq    = require('groq-sdk');

const app  = express();
app.use(cors());
app.use(express.json());

const groq = new Groq({ apiKey: process.env.GROQ_API_KEY });

const CATEGORY_PROMPTS = {
  Storstad: 'en svensk storstad med kultur, nöjen och shopping',
  Natur:    'en naturskön destination med natur, nationalparker eller friluftsliv i Sverige',
  Havet:    'en havs- eller skärgårdsdestination längs Sveriges kust'
};

app.post('/suggest', async (req, res) => {
  const { category, from } = req.body;

  if (!category || !CATEGORY_PROMPTS[category]) {
    return res.status(400).json({ error: 'Ogiltig kategori' });
  }

  const fromText = from ? `från ${from}` : 'i Sverige';
  const prompt =
    `Du är en tågreseexpert i Sverige. En resenär vill resa ${fromText} och söker ${CATEGORY_PROMPTS[category]}.\n\n` +
    `Ge ETT perfekt destinationsförslag som går bra att nå med tåg. ` +
    `Svara ENBART med ortnamnet, ingenting annat. Exempel: "Göteborg"`;

  try {
    const completion = await groq.chat.completions.create({
      model: 'llama-3.3-70b-versatile',
      messages: [{ role: 'user', content: prompt }],
      temperature: 0.8,
      max_tokens: 20
    });

    const destination = completion.choices[0].message.content.trim().replace(/[".]/g, '');
    res.json({ destination });
  } catch (e) {
    console.error('Groq error:', e.message);
    res.status(500).json({ error: 'Kunde inte hämta förslag. Försök igen.' });
  }
});

app.get('/health', (_req, res) => res.json({ ok: true }));

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`MiniPrisTåget backend kör på port ${PORT}`));
