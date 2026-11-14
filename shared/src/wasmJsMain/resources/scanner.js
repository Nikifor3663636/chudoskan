// Штрихкод с фото: камера iPhone через <input capture>, распознавание — ZXing (EAN-13/8, UPC, Code128).
globalThis.chudoScan = {
  photo() {
    return new Promise((resolve, reject) => {
      const input = document.createElement("input");
      input.type = "file";
      input.accept = "image/*";
      input.setAttribute("capture", "environment");
      input.onchange = async () => {
        const file = input.files && input.files[0];
        if (!file) return resolve(null);
        const url = URL.createObjectURL(file);
        try {
          const hints = new Map([[ZXing.DecodeHintType.TRY_HARDER, true]]);
          const reader = new ZXing.BrowserMultiFormatReader(hints);
          const result = await reader.decodeFromImageUrl(url);
          resolve(result.getText());
        } catch (e) {
          resolve(null); // не нашли штрихкод на фото
        } finally {
          URL.revokeObjectURL(url);
        }
      };
      input.click();
    });
  },
};
