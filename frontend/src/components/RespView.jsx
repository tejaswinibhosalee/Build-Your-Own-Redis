// Shows raw RESP bytes, making the invisible "\r\n" line endings visible and
// highlighting the one-character type prefixes that frame every value:
//   *  array      $  bulk string      +  simple string      -  error      :  integer
export default function RespView({ title, resp }) {
  const parts = resp.split('\r\n');
  const bytes = new TextEncoder().encode(resp).length;
  let expectPayload = false; // the line after "$5" is raw data, not a prefix

  const lines = [];
  parts.forEach((part, i) => {
    if (i === parts.length - 1 && part === '') return; // artifact of the trailing \r\n
    let content;
    if (!expectPayload && part.length && '*$+-:'.includes(part[0])) {
      content = (
        <>
          <span className="pfx">{part[0]}</span>
          {part.slice(1)}
        </>
      );
      expectPayload = part[0] === '$' && !part.startsWith('$-1');
    } else {
      content = part;
      expectPayload = false;
    }
    lines.push(
      <span key={i}>
        {content}
        {i < parts.length - 1 && <span className="crlf">\r\n</span>}
        {'\n'}
      </span>,
    );
  });

  return (
    <div className="wire-box">
      <div className="wire-title">
        <span>{title}</span>
        <span>{bytes} bytes</span>
      </div>
      <pre className="wire-body">{lines}</pre>
    </div>
  );
}
