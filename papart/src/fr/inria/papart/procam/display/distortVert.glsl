/* //vertex shader */


#define PROCESSING_TEXTURE_SHADER

uniform mat4 transform;
uniform mat4 texMatrix;

attribute vec4 position;
attribute vec4 color;
attribute vec2 texCoord;

varying vec4 vertColor;
varying vec4 vertTexCoord;

void main() {

  // Useless... ?  
  vertColor = color;
  vertTexCoord = texMatrix * vec4(texCoord, 1.0, 1.0);

  // Deprecated 
  // gl_TexCoord[0] = gl_MultiTexCoord0; 

  gl_Position = transform * position;
  //  gl_Position = ftransform();
}


/* void main() */
/* { */
/*   gl_TexCoord[0] = gl_MultiTexCoord0; */
/*   gl_Position = ftransform(); */

/* } */
