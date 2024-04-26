# ml-kit-boofcv-barcode-scanner-example
Experimental scanning of barcodes using multiple detectors. Toggle between

 * parallell (comparison of ml-kit and boofcv)
 * coop mode: if one scanner comes up empty, let the other try 
   * ml-kit then boofcv
   * boofcv then ml-kit
 
Experiment with adjusting camera using `Camera2Interop.Extender` and such.

By default set up so that

 * ml-kit: QR + Aztec
 * boofcv: Aztec

