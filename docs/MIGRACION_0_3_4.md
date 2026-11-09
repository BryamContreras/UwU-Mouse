# Migrar a UwU Mouse 0.3.4

Esta entrega usa **`dev.uwumouse.app`** como identificador Android. El nombre visible sigue siendo **UwU Mouse**, pero Android guarda sus datos y permisos como los de una aplicación nueva. El certificado compartido no convierte un cambio de identificador en una actualización sobre la instalación anterior.

## Antes de instalar

1. En la aplicación anterior, exporta cada perfil que quieras conservar mediante **Exportar**. Guarda los archivos `.json` generados por la app en una carpeta accesible, como Descargas; no necesitas cambiarles el nombre para importarlos. Un archivo exportado contiene un perfil con su calibración; exportar uno no respalda automáticamente los demás.
2. Cierra la sesión desde Inicio y espera a que desaparezca la tarjeta de sesión. Si aparece un cierre pendiente, resuélvelo antes de cambiar el motor.
3. Abre **Ajustes → Conexión → Laboratorio y diagnóstico → Cerrar backend ADB**. El proceso anterior debe estar cerrado antes de activar el nuevo.

Mantén disponible la instalación anterior hasta comprobar que los archivos exportados se importan correctamente. Desinstalarla o borrar sus datos elimina su almacenamiento local.

## Instalar y recuperar los perfiles

1. Instala el APK de 0.3.4 correspondiente a tu arquitectura. La mayoría de teléfonos actuales usa **arm64-v8a**; consulta la arquitectura de tu dispositivo si no la conoces.
2. Abre la aplicación nueva y concede **Mostrar sobre otras apps** desde Inicio o Ajustes de conexión. Permite notificaciones si vas a introducir el código de activación desde la notificación.
3. Activa el motor desde **Ajustes → Conexión → Activar motor**, con el emparejamiento inalámbrico de esta instalación, o usa el [activador de PC](ACTIVAR_PC.md).
4. Agrega el juego correspondiente, abre sus opciones y usa **Importar perfil**, debajo de **Nuevo perfil**, para seleccionar cada archivo guardado. La importación conserva el formato del perfil y valida sus datos; no vuelve a conceder permisos de Android.
5. Comprueba el perfil seleccionado y la calibración en la pantalla donde vas a jugar. Un cambio de teléfono, resolución o geometría de la ventana puede requerir recalibrar.
6. Reconfigura las preferencias generales de apariencia que necesites. Los archivos de perfil no exportan la identidad ADB, los permisos ni todas las preferencias globales de la aplicación.

La instalación anterior puede retirarse después de verificar la nueva y los respaldos. Sus procesos no son necesarios para jugar con esta entrega.

## Actualizaciones posteriores

Con **el mismo identificador y certificado** de esta aplicación, instala el APK nuevo encima del anterior, sin desinstalar. Cierra antes la sesión y el backend; reactiva el motor después para que ejecute el código actualizado. Un reinicio o la pérdida del motor puede requerir reactivarlo, sin volver a crear los perfiles.
