import { useEffect, useState } from 'react';

// A small message that pops up at the bottom and fades away after a moment.
export default function Toast({ toast }) {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (!toast) return undefined;
    setVisible(true);
    const id = setTimeout(() => setVisible(false), 2600);
    return () => clearTimeout(id);
  }, [toast]);

  if (!toast || !visible) return null;
  return (
    <div className="toast" role="status">
      {toast.message}
    </div>
  );
}
