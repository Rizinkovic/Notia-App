const d = {
  en: { notes:'Notes', search:'Search notes', all:'All', newCat:'New category', add:'Add', empty:'No notes yet. Tap + to write your first one.',
    title:'Title', body:'Start writing…', chars:'characters', created:'Created', modified:'Modified', share:'Share', sharePdf:'Share as PDF', shareText:'Share as text',
    cancel:'Cancel', del:'Delete', delNote:'Delete this note?', delCat:'Delete category? Its notes stay, uncategorised.', settings:'Settings',
    dark:'Dark theme', language:'Language', about:'About Notia', aboutTxt:'Notia mobile — the notes, tasks and reading companion of Notia for Windows. By Song Richard.', noCat:'No category' },
  fr: { notes:'Notes', search:'Rechercher', all:'Toutes', newCat:'Nouvelle catégorie', add:'Ajouter', empty:'Aucune note. Touchez + pour écrire la première.',
    title:'Titre', body:'Commencez à écrire…', chars:'caractères', created:'Créée', modified:'Modifiée', share:'Partager', sharePdf:'Partager en PDF', shareText:'Partager en texte',
    cancel:'Annuler', del:'Supprimer', delNote:'Supprimer cette note ?', delCat:'Supprimer la catégorie ? Ses notes restent, sans catégorie.', settings:'Paramètres',
    dark:'Thème sombre', language:'Langue', about:'À propos de Notia', aboutTxt:'Notia mobile — le compagnon notes, tâches et lecture de Notia pour Windows. Par Song Richard.', noCat:'Sans catégorie' },
};
export type Lang = 'en' | 'fr';
export type Key = keyof typeof d.en;
export const tr = (l: Lang, k: Key) => d[l][k];
export const L = (l: Lang, en: string, fr: string) => (l === 'fr' ? fr : en);
